package com.togather.content.slack.service;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.common.response.PageResponse;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentDetailDTO;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.SlackFile;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.content.slack.dto.SlackChannelResponse;
import com.togather.content.slack.dto.SlackMessageResponse;
import com.togather.content.slack.dto.ThreadDetailResponse;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.dto.SyncStatusResponse;
import com.togather.sync.service.SyncService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-03-02 SlackContentService (UC-04-01). 화면은 동기화해 둔 content_ref를 먼저 보여준다. (README 성능 목표)
 * 외부 API를 부르는 메서드는 DB 트랜잭션 밖에서 실행한다. (NOT_SUPPORTED)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SlackContentService {

    /** AI 업무 보조가 보는 기간 */
    static final int USER_MESSAGE_DAYS = 14;
    private static final int RECENT_LIMIT = 200;

    private final ContentRefRepository contentRefRepository;
    private final ContentRefService contentRefService;
    private final IntegrationService integrationService;
    private final SlackApiClient slackApiClient;
    private final SlackDirectory slackDirectory;
    private final SlackSyncAdapter slackSyncAdapter;
    private final SyncService syncService;

    // ===================== 화면 (UI-04) =====================

    /** 연동 대상으로 선택한 채널 목록과 동기화 상태 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<SlackChannelResponse> getChannels(Long workspaceId, Long userId) {
        List<String> channelIds = integrationService.getConnectionTargets(workspaceId, Provider.SLACK);
        Optional<String> token = findToken(workspaceId, userId);
        Map<String, SyncStatusResponse> statuses = syncService.getStatuses(workspaceId, Provider.SLACK).stream()
                .collect(Collectors.toMap(SyncStatusResponse::targetId, Function.identity(), (a, b) -> a));
        return channelIds.stream().map(channelId -> {
            String name = token.map(t -> slackDirectory.getChannelName(workspaceId, t, channelId))
                    .orElseGet(() -> slackDirectory.getCachedChannelName(workspaceId, channelId));
            SyncStatusResponse status = statuses.get(channelId);
            return new SlackChannelResponse(channelId, name, status == null ? null : status.lastSyncedAt(),
                    status != null && status.failed(), status == null ? null : status.lastError());
        }).toList();
    }

    /** 채널의 메시지 (최신순 페이지). 화면에서 시간순으로 뒤집어 표시한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PageResponse<SlackMessageResponse> getChannelMessages(Long workspaceId, Long userId, String channelId,
                                                                 Pageable pageable) {
        checkLinkedChannel(workspaceId, channelId);
        Page<ContentRef> page = contentRefRepository
                .findByWorkspaceIdAndProviderAndChannelIdAndParentRefIdIsNullOrderByCreatedAtDesc(
                        workspaceId, Provider.SLACK, channelId, pageable);
        Map<Long, Long> replyCounts = contentRefService.countReplies(
                page.getContent().stream().map(ContentRef::getContentRefId).toList());
        String mySlackId = findMySlackId(workspaceId, userId).orElse(null);
        return PageResponse.from(page, ref -> toResponse(ref,
                replyCounts.getOrDefault(ref.getContentRefId(), 0L).intValue(), mySlackId));
    }

    /**
     * 스레드 본문·답글·원본 링크. Slack에서 최신 답글을 가져와 저장한 뒤 보여주고,
     * Slack 호출이 실패하면 저장된 내용만 보여준다. (refreshFailed=true)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ThreadDetailResponse getThreadDetail(Long workspaceId, Long userId, String channelId, String messageTs) {
        checkLinkedChannel(workspaceId, channelId);
        ContentRef root = contentRefRepository.findByWorkspaceIdAndProviderAndOriginId(workspaceId, Provider.SLACK,
                        SlackMessageFormat.originId(channelId, messageTs))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        boolean refreshFailed = false;
        if (!root.isOriginDeleted()) {
            try {
                String token = integrationService.getAccessToken(workspaceId, userId, Provider.SLACK);
                slackSyncAdapter.syncReplies(workspaceId, token, channelId,
                        slackDirectory.getChannelName(workspaceId, token, channelId), messageTs,
                        root.getContentRefId(), null);
            } catch (BusinessException e) {
                log.info("스레드 새로고침 실패, 저장된 내용 표시: workspaceId={}, error={}", workspaceId, e.getErrorCode());
                refreshFailed = true;
            }
        }
        List<ContentRef> replies = contentRefRepository.findAllByParentRefIdOrderByCreatedAtAsc(root.getContentRefId());
        String mySlackId = findMySlackId(workspaceId, userId).orElse(null);
        return new ThreadDetailResponse(toResponse(root, replies.size(), mySlackId),
                replies.stream().map(r -> toResponse(r, 0, mySlackId)).toList(), root.getDeepLink(), refreshFailed);
    }

    // ===================== 작성·수정·삭제 (UC-04-02 ~ 04) =====================

    /** 채널에 메시지를 작성하고 content_ref로 저장한다. 본인 Slack 연결이 있어야 본인 이름으로 보낼 수 있다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SlackMessageResponse createMessage(Long workspaceId, Long userId, String channelId, String body) {
        return post(workspaceId, userId, channelId, null, null, body);
    }

    /** 스레드에 답글을 작성한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SlackMessageResponse createReply(Long workspaceId, Long userId, String channelId, String threadTs,
                                            String body) {
        ContentRef root = getMessage(workspaceId, channelId, threadTs);
        if (root.getParentRefId() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT); // 답글에는 답글을 달 수 없다.
        }
        return post(workspaceId, userId, channelId, threadTs, root.getContentRefId(), body);
    }

    /** 본인 메시지만 수정할 수 있다. 남의 메시지면 Slack이 거부한다. (SLACK_ACTION_FORBIDDEN) */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SlackMessageResponse updateMessage(Long workspaceId, Long userId, String channelId, String messageTs,
                                              String body) {
        validateInput(body);
        ContentRef ref = getMessage(workspaceId, channelId, messageTs);
        slackApiClient.updateMessage(ownToken(workspaceId, userId), channelId, messageTs, body);
        List<SlackFile> files = SlackMessageFormat.parse(ref.getContent()).attachments().stream()
                .map(a -> new SlackFile(a.name(), a.url(), null))
                .toList();
        contentRefService.upsert(new ContentRefUpsert(workspaceId, null, Provider.SLACK, ref.getContentType(),
                ref.getOriginId(), channelId, ref.getParentRefId(), ref.getTitle(),
                SlackMessageFormat.toContent(body, files), ref.getDeepLink(), ref.getCreatedAt(),
                LocalDateTime.now(SlackApiClient.ZONE), null, null));
        return reload(ref.getContentRefId(), workspaceId, userId);
    }

    /** 본인 메시지만 삭제할 수 있다. content_ref는 지우지 않고 원본 삭제로 표시한다. (근거 발언·Task 연결 보존) */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean deleteMessage(Long workspaceId, Long userId, String channelId, String messageTs) {
        ContentRef ref = getMessage(workspaceId, channelId, messageTs);
        slackApiClient.deleteMessage(ownToken(workspaceId, userId), channelId, messageTs);
        contentRefService.markOriginDeleted(ref.getContentRefId());
        return true;
    }

    private SlackMessageResponse post(Long workspaceId, Long userId, String channelId, String threadTs,
                                      Long rootRefId, String body) {
        validateInput(body);
        checkLinkedChannel(workspaceId, channelId);
        String token = ownToken(workspaceId, userId);
        SlackMessage posted = slackApiClient.postMessage(token, channelId, threadTs, body);
        Map<String, SlackUser> users = posted.userId() == null ? Map.of()
                : slackDirectory.getUsers(workspaceId, token, List.of(posted.userId()));
        ContentRefDTO saved = slackSyncAdapter.save(workspaceId, token, channelId,
                slackDirectory.getChannelName(workspaceId, token, channelId), posted, rootRefId, users);
        if (saved == null) {
            // Slack에는 전송됐으므로 다음 동기화에서 저장된다. 화면이 다시 보내지 않도록 성공으로 응답한다.
            log.warn("작성한 메시지 저장 실패, 다음 동기화에서 반영: workspaceId={}, channelId={}", workspaceId, channelId);
            return new SlackMessageResponse(null, channelId, posted.ts(), posted.userId(), null, body, List.of(),
                    posted.createdAt(), false, 0, null, false, true, true);
        }
        return reload(saved.contentRefId(), workspaceId, userId);
    }

    private SlackMessageResponse reload(Long contentRefId, Long workspaceId, Long userId) {
        ContentRef ref = contentRefRepository.findById(contentRefId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return toResponse(ref, (int) contentRefRepository.countByParentRefId(contentRefId),
                findMySlackId(workspaceId, userId).orElse(null));
    }

    private ContentRef getMessage(Long workspaceId, String channelId, String messageTs) {
        checkLinkedChannel(workspaceId, channelId);
        return contentRefRepository.findByWorkspaceIdAndProviderAndOriginId(workspaceId, Provider.SLACK,
                        SlackMessageFormat.originId(channelId, messageTs))
                .filter(ref -> !ref.isOriginDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 팀원의 토큰으로 대신 보내지 않도록 본인 연결만 허용한다. */
    private String ownToken(Long workspaceId, Long userId) {
        if (!integrationService.hasConnection(workspaceId, userId, Provider.SLACK)) {
            throw new BusinessException(ErrorCode.SLACK_OWN_CONNECTION_REQUIRED);
        }
        return integrationService.getAccessToken(workspaceId, userId, Provider.SLACK);
    }

    /** 명세서 validateInput: 빈 본문, 4,000자 초과 거부 */
    private static void validateInput(String body) {
        if (body == null || body.isBlank() || body.length() > 4000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    // ===================== 다른 파트 제공 (docs/shared-interfaces.md) =====================

    /** 워크스페이스의 Slack 대화(스레드 root) 최근 200개 (Task 관련 자료 후보) */
    public List<ContentRefDTO> getContents(Long workspaceId) {
        return contentRefService.toDtos(contentRefRepository
                .findAllByWorkspaceIdAndProviderAndParentRefIdIsNullOrderByLastModifiedAtDesc(workspaceId,
                        Provider.SLACK)
                .stream().filter(ref -> !ref.isOriginDeleted()).limit(RECENT_LIMIT).toList());
    }

    /**
     * 사용자가 쓰거나 멘션된 최근 14일 메시지 (AI 업무 보조 후보 추출).
     * Slack 계정을 이메일로 찾지 못하면 빈 목록. [변경] 워크스페이스별 후보를 만들기 위해 workspaceId 추가
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ContentRefDTO> getContentsByUser(Long workspaceId, Long userId) {
        return toDtos(findMessagesByUser(workspaceId, userId));
    }

    /** AI 업무 보조용: 작성자 정보까지 필요한 같은 파트 내부 호출 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ContentRef> findMessagesByUser(Long workspaceId, Long userId) {
        Optional<String> slackId = findMySlackId(workspaceId, userId);
        if (slackId.isEmpty()) {
            return List.of();
        }
        LocalDateTime after = LocalDateTime.now(SlackApiClient.ZONE).minusDays(USER_MESSAGE_DAYS);
        LinkedHashSet<ContentRef> result = new LinkedHashSet<>(contentRefRepository
                .findAllByWorkspaceIdInAndProviderAndAuthorExternalIdInAndCreatedAtAfterOrderByCreatedAtDesc(
                        List.of(workspaceId), Provider.SLACK, List.of(slackId.get()), after));
        result.addAll(contentRefRepository.findByContentPattern(List.of(workspaceId), Provider.SLACK,
                "%<@" + slackId.get() + ">%", after));
        return result.stream().filter(ref -> !ref.isOriginDeleted()).toList();
    }

    /** 통합 검색·협업 맥락의 상세. 스레드면 참여자 이름을 함께 준다. */
    public ContentDetailDTO getContentDetail(Long contentRefId) {
        ContentRef ref = contentRefRepository.findById(contentRefId)
                .filter(r -> r.getProvider() == Provider.SLACK)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<ContentRef> thread = new ArrayList<>();
        thread.add(ref);
        if (ref.getParentRefId() == null) {
            thread.addAll(contentRefRepository.findAllByParentRefIdOrderByCreatedAtAsc(contentRefId));
        }
        List<String> participants = thread.stream().map(ContentRef::getAuthorName).filter(Objects::nonNull)
                .distinct().toList();
        String body = SlackMessageFormat.parse(ref.isOriginDeleted() ? ref.getSnapshot() : ref.getContent()).text();
        return new ContentDetailDTO(ref.getContentRefId(), Provider.SLACK, ref.getTitle(),
                slackDirectory.renderMentions(ref.getWorkspaceId(), body), ref.getDeepLink(), null,
                ref.getCreatedAt(), participants, null, ref.isOriginDeleted());
    }

    // ===================== 논의 분석용 (B 내부) =====================

    /** since 이후 새 답글이 달렸거나 수정된 스레드 */
    public List<ContentRefDTO> getUpdatedThreads(Long workspaceId, LocalDateTime since) {
        return contentRefService.toDtos(contentRefRepository.findUpdatedThreads(workspaceId, Provider.SLACK, since));
    }

    /** 스레드의 root와 답글 중 after 이후 메시지 (after가 null이면 전체). 시간순 */
    public List<ThreadMessage> getThreadMessages(Long threadId, LocalDateTime after) {
        ContentRef root = contentRefRepository.findById(threadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<ContentRef> messages = new ArrayList<>();
        messages.add(root);
        messages.addAll(contentRefRepository.findAllByParentRefIdOrderByCreatedAtAsc(threadId));
        return messages.stream()
                .filter(m -> after == null || m.getCreatedAt().isAfter(after)
                        || (m.getLastModifiedAt() != null && m.getLastModifiedAt().isAfter(after)))
                .map(m -> new ThreadMessage(m.getContentRefId(), m.getAuthorExternalId(), m.getAuthorName(),
                        SlackMessageFormat.parse(m.getContent()).text(), m.getCreatedAt(), m.getLastModifiedAt()))
                .toList();
    }

    public record ThreadMessage(Long contentRefId, String authorExternalId, String authorName, String text,
                                LocalDateTime createdAt, LocalDateTime lastModifiedAt) {
    }

    // ===================== 내부 =====================

    SlackMessageResponse toResponse(ContentRef ref, int replyCount, String mySlackId) {
        SlackMessageFormat.Parsed parsed = SlackMessageFormat.parse(
                ref.isOriginDeleted() ? ref.getSnapshot() : ref.getContent());
        boolean edited = ref.getLastModifiedAt() != null && ref.getCreatedAt() != null
                && ref.getLastModifiedAt().isAfter(ref.getCreatedAt());
        return new SlackMessageResponse(ref.getContentRefId(), ref.getChannelId(),
                SlackMessageFormat.tsOf(ref.getOriginId()), ref.getAuthorExternalId(), ref.getAuthorName(),
                slackDirectory.renderMentions(ref.getWorkspaceId(), parsed.text()), parsed.attachments(),
                ref.getCreatedAt(), edited, replyCount, ref.getDeepLink(), ref.isOriginDeleted(), ref.isSyncFailed(),
                mySlackId != null && mySlackId.equals(ref.getAuthorExternalId()));
    }

    void checkLinkedChannel(Long workspaceId, String channelId) {
        if (!integrationService.getConnectionTargets(workspaceId, Provider.SLACK).contains(channelId)) {
            throw new BusinessException(ErrorCode.SLACK_CHANNEL_NOT_LINKED);
        }
    }

    Optional<String> findMySlackId(Long workspaceId, Long userId) {
        return findToken(workspaceId, userId).flatMap(t -> slackDirectory.findSlackUserId(workspaceId, t, userId));
    }

    private Optional<String> findToken(Long workspaceId, Long userId) {
        try {
            return integrationService.findAccessToken(workspaceId, userId, Provider.SLACK);
        } catch (BusinessException e) {
            return Optional.empty();
        }
    }

    private List<ContentRefDTO> toDtos(Collection<ContentRef> refs) {
        return contentRefService.toDtos(refs);
    }
}
