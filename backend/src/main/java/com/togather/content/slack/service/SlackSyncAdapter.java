package com.togather.content.slack.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.service.TeamTokenResolver;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.HistoryPage;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Slack 채널 증분 동기화 (B-04). 연동 대상으로 선택한 채널의 메시지와 스레드 답글을 content_ref에 저장한다.
 *
 * <ul>
 *   <li>처음: 최근 30일</li>
 *   <li>이후: 마지막 동기화 3일 전부터 다시 훑는다. conversations.history는 메시지 작성 시각 기준이라,
 *       오래된 스레드에 새로 달린 답글과 수정된 메시지를 놓치지 않으려면 겹쳐서 봐야 한다.</li>
 *   <li>답글은 root의 latest_reply가 마지막 동기화 이후인 스레드만 가져온다. (호출 한도 절약)</li>
 *   <li>Slack에서 지운 메시지: Slack은 지웠다고 알려 주지 않고 결과에서 빼기만 한다. 그래서 이번에 훑은 범위 안에서
 *       저장된 메시지가 응답에 없으면 원본 삭제로 표시한다. 행은 지우지 않는다. (근거 발언·Task 연결 보존)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackSyncAdapter implements SyncAdapter {

    static final Duration FIRST_SYNC_RANGE = Duration.ofDays(30);
    static final Duration LOOKBACK = Duration.ofDays(3);
    /** 동기화 도중 달린 답글을 놓치지 않도록 조금 겹쳐서 가져온다. */
    static final Duration REPLY_OVERLAP = Duration.ofMinutes(2);
    /** Slack 시스템 메시지(입장·퇴장 등)는 저장하지 않는다. */
    private static final Set<String> SKIPPED_SUBTYPES = Set.of(
            "channel_join", "channel_leave", "channel_topic", "channel_purpose", "channel_name",
            "channel_archive", "channel_unarchive", "group_join", "group_leave", "pinned_item", "unpinned_item");
    /** 답글이 있는 root를 지우면 Slack이 "This message was deleted." 자리표시 메시지로 남긴다. */
    private static final String TOMBSTONE = "tombstone";

    private final SlackApiClient slackApiClient;
    private final SlackDirectory slackDirectory;
    private final ContentRefService contentRefService;
    private final IntegrationService integrationService;
    private final TeamTokenResolver teamTokenResolver;

    @Override
    public Provider provider() {
        return Provider.SLACK;
    }

    @Override
    public List<SyncTarget> findTargets(Long workspaceId, Long requestedBy) {
        return integrationService.getConnectionTargets(workspaceId, Provider.SLACK).stream()
                .map(SyncTarget::team)
                .toList();
    }

    @Override
    public SyncResult sync(SyncContext context) {
        LocalDateTime startedAt = LocalDateTime.now(SlackApiClient.ZONE);
        String channelId = context.targetId();
        String token = teamTokenResolver.tokenFor(context.workspaceId(), Provider.SLACK, channelId,
                context.requestedBy());
        LocalDateTime oldest = context.since() == null
                ? startedAt.minus(FIRST_SYNC_RANGE)
                : context.since().minus(LOOKBACK);
        String channelName = slackDirectory.getChannelName(context.workspaceId(), token, channelId);

        HistoryPage page = slackApiClient.getHistory(token, channelId, oldest, context.since() == null ? 10 : 5);
        Map<String, SlackUser> users = slackDirectory.getUsers(context.workspaceId(), token,
                page.messages().stream().map(SlackMessage::userId).toList());

        int saved = 0;
        Set<String> received = new HashSet<>();
        for (SlackMessage message : page.messages()) {
            received.add(SlackMessageFormat.originId(channelId, message.ts()));
            // "채널에도 보내기"로 단 답글(thread_broadcast)도 history에 나온다. 답글은 스레드에서 저장한다.
            if (isSkipped(message) || message.isReply()) {
                continue;
            }
            Long rootRefId;
            if (TOMBSTONE.equals(message.subtype())) {
                // 지워진 root: 자리표시 문구로 덮어쓰지 않고 저장된 사본을 원본 삭제로 표시한다. 답글은 계속 받는다.
                rootRefId = markDeleted(context.workspaceId(), channelId, message.ts()).orElse(null);
            } else {
                ContentRefDTO root = save(context.workspaceId(), token, channelId, channelName, message, null, users);
                if (root == null) {
                    continue;
                }
                rootRefId = root.contentRefId();
                saved++;
            }
            if (rootRefId != null && message.hasReplies() && hasNewReplies(message, context.since())) {
                saved += syncReplies(context.workspaceId(), token, channelId, channelName, message.ts(),
                        rootRefId, context.since());
            }
        }
        // 일부만 받았으면 빠진 메시지가 지워진 건지 못 받은 건지 알 수 없어서 건너뛴다.
        if (!page.truncated()) {
            contentRefService.markMissingRootsDeleted(context.workspaceId(), Provider.SLACK, channelId, oldest,
                    startedAt, received);
        }
        return new SyncResult(saved, startedAt);
    }

    /**
     * 스레드 답글을 저장한다. 스레드 상세 조회(SlackContentService)에서도 쓴다.
     *
     * <p>since가 null이면 스레드 전체를 받으므로, 저장된 답글 중 응답에 없는 것을 원본 삭제로 표시한다.
     * 스레드 root가 지워져 Slack이 thread_not_found를 주면 root와 답글을 모두 원본 삭제로 표시한다.</p>
     */
    int syncReplies(Long workspaceId, String token, String channelId, String channelName, String threadTs,
                    Long rootRefId, LocalDateTime since) {
        HistoryPage thread;
        try {
            // since 이후만 받으면 빠진 답글이 지워진 건지 알 수 없으므로 일부(truncated)로 취급한다.
            thread = since == null
                    ? slackApiClient.getThread(token, channelId, threadTs)
                    : new HistoryPage(slackApiClient.getReplies(token, channelId, threadTs,
                            since.minus(REPLY_OVERLAP)), true);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw e;
            }
            contentRefService.markThreadDeleted(rootRefId);
            return 0;
        }
        List<SlackMessage> replies = thread.messages();
        Map<String, SlackUser> users = slackDirectory.getUsers(workspaceId, token,
                replies.stream().map(SlackMessage::userId).toList());
        int saved = 0;
        Set<String> received = new HashSet<>();
        for (SlackMessage reply : replies) {
            received.add(SlackMessageFormat.originId(channelId, reply.ts()));
            if (!reply.isReply()) {
                if (TOMBSTONE.equals(reply.subtype())) {
                    contentRefService.markOriginDeleted(rootRefId);
                }
                continue;
            }
            if (isSkipped(reply)) {
                continue;
            }
            if (save(workspaceId, token, channelId, channelName, reply, rootRefId, users) != null) {
                saved++;
            }
        }
        if (!thread.truncated()) {
            contentRefService.markMissingRepliesDeleted(rootRefId, received);
        }
        return saved;
    }

    /** 저장된 메시지를 원본 삭제로 표시한다. 저장된 적 없으면 빈 값 */
    private Optional<Long> markDeleted(Long workspaceId, String channelId, String ts) {
        Optional<Long> id = contentRefService.findByOrigin(workspaceId, Provider.SLACK,
                SlackMessageFormat.originId(channelId, ts)).map(ContentRefDTO::contentRefId);
        id.ifPresent(contentRefService::markOriginDeleted);
        return id;
    }

    /** 저장에 실패하면 해당 메시지에 실패 표시만 하고 다음 메시지로 넘어간다. (recordSyncFailure) */
    ContentRefDTO save(Long workspaceId, String token, String channelId, String channelName, SlackMessage message,
                       Long parentRefId, Map<String, SlackUser> users) {
        String originId = SlackMessageFormat.originId(channelId, message.ts());
        try {
            SlackUser author = users.get(message.userId());
            return contentRefService.upsert(new ContentRefUpsert(workspaceId, null, Provider.SLACK,
                    ContentType.MESSAGE, originId, channelId, parentRefId, "#" + channelName,
                    SlackMessageFormat.toContent(message.text(), message.files()),
                    slackDirectory.permalink(workspaceId, token, channelId, message.ts(), message.threadTs()),
                    message.createdAt(), message.editedAt() != null ? message.editedAt() : message.createdAt(),
                    message.userId(), author == null ? null : author.name()));
        } catch (RuntimeException e) {
            log.warn("Slack 메시지 저장 실패: workspaceId={}, channelId={}, error={}", workspaceId, channelId,
                    e instanceof BusinessException be ? be.getErrorCode() : e.getClass().getSimpleName());
            try {
                contentRefService.markSyncFailed(workspaceId, Provider.SLACK, originId);
            } catch (RuntimeException ignored) {
                // 실패 표시도 못 하면 다음 동기화에서 다시 시도한다.
            }
            return null;
        }
    }

    private static boolean isSkipped(SlackMessage message) {
        return message.subtype() != null && SKIPPED_SUBTYPES.contains(message.subtype());
    }

    private static boolean hasNewReplies(SlackMessage root, LocalDateTime since) {
        if (since == null || root.latestReplyTs() == null) {
            return true;
        }
        return SlackApiClient.toLocal(root.latestReplyTs()).isAfter(since.minus(REPLY_OVERLAP));
    }
}
