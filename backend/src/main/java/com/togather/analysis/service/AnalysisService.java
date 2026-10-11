package com.togather.analysis.service;

import com.togather.analysis.domain.AnalysisEvidence;
import com.togather.analysis.domain.AnalysisItem;
import com.togather.analysis.domain.DiscussionAnalysis;
import com.togather.analysis.dto.AnalysisEvidenceDTO;
import com.togather.analysis.dto.AnalysisItemDTO;
import com.togather.analysis.dto.AnalysisItemRequest;
import com.togather.analysis.dto.AnalysisItemResponse;
import com.togather.analysis.dto.AnalysisItemUpdateRequest;
import com.togather.analysis.dto.AnalysisResultDTO;
import com.togather.analysis.dto.AnalysisSummaryDTO;
import com.togather.analysis.dto.ThreadMessageResponse;
import com.togather.analysis.repository.AnalysisEvidenceRepository;
import com.togather.analysis.repository.AnalysisItemRepository;
import com.togather.analysis.repository.DiscussionAnalysisRepository;
import com.togather.analysis.service.AnalysisStore.NewItem;
import com.togather.analysis.service.DiscussionAnalyzer.Candidate;
import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.enums.AnalysisStatus;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.content.slack.service.SlackContentService;
import com.togather.content.slack.service.SlackContentService.ThreadMessage;
import com.togather.content.slack.service.SlackDirectory;
import com.togather.content.slack.service.SlackMessageFormat;
import com.togather.workspace.service.WorkspaceMemberService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.togather.content.service.TeamTokenResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-04-03 AnalysisService (UC-08-01 ~ 02). AI 분석 결과는 사용자가 검토·확정하기 전에는 외부에 반영하지 않는다.
 * (README 12번) 분석 범위: 이전 분석의 analyzedUntil 이후 메시지만 새로 분석한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisService {

    /** 마지막 메시지 이후 이 시간 동안 새 메시지가 없으면 논의가 끝난 것으로 본다. (자동 수집 분석) */
    static final Duration SETTLE_TIME = Duration.ofMinutes(30);
    private static final int PREVIEW_LENGTH = 80;
    private static final Set<AnalysisStatus> ANALYZED = EnumSet.of(
            AnalysisStatus.REVIEWING, AnalysisStatus.NO_CONTENT, AnalysisStatus.CONFIRMED);

    private final DiscussionAnalysisRepository analysisRepository;
    private final AnalysisItemRepository itemRepository;
    private final AnalysisEvidenceRepository evidenceRepository;
    private final AnalysisStore analysisStore;
    private final DiscussionAnalyzer discussionAnalyzer;
    private final ContentRefRepository contentRefRepository;
    private final SlackContentService slackContentService;
    private final SlackDirectory slackDirectory;
    private final TeamTokenResolver teamTokenResolver;
    private final UserService userService;
    private final WorkspaceMemberService workspaceMemberService;

    /** 지금 분석 중인 스레드 (동시 분석 방지) */
    private final Set<Long> runningThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ===================== 분석 (SD-08-01) =====================

    /**
     * 선택한 스레드의 새 메시지를 즉시 분석한다. 새 메시지가 없으면 가장 최근 분석을 그대로 돌려준다.
     *
     * @throws BusinessException AI_CALL_FAILED(분석 실패, 다시 시도 가능), ANALYSIS_IN_PROGRESS
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisResultDTO analyzeThread(Long workspaceId, Long contentRefId, Long requestedBy) {
        ContentRef root = getThreadRoot(workspaceId, contentRefId);
        // 같은 스레드를 동시에 두 번 분석하지 않는다. (서버 한 대 기준)
        if (!runningThreads.add(contentRefId)) {
            throw new BusinessException(ErrorCode.ANALYSIS_IN_PROGRESS);
        }
        try {
            return analyze(workspaceId, root, requestedBy);
        } finally {
            runningThreads.remove(contentRefId);
        }
    }

    private AnalysisResultDTO analyze(Long workspaceId, ContentRef root, Long requestedBy) {
        Long contentRefId = root.getContentRefId();
        List<ThreadMessage> messages = slackContentService.getThreadMessages(contentRefId, null);
        List<DiscussionAnalysis> history = analysisRepository.findAllByContentRefIdOrderByAnalysisIdAsc(contentRefId);
        LocalDateTime analyzedUntil = determineAnalysisRange(history);
        LocalDateTime lastMessageAt = lastMessageAt(messages);

        Optional<DiscussionAnalysis> last = history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
        if (last.isPresent() && analyzedUntil != null && !lastMessageAt.isAfter(analyzedUntil)) {
            Long latestId = history.stream().filter(a -> a.getStatus() != AnalysisStatus.FAILED)
                    .reduce((a, b) -> b).orElse(last.get()).getAnalysisId();
            return getAnalysis(workspaceId, latestId); // 새 메시지 없음
        }
        // 확정 전 분석이 있으면 그 분석에 이어서 항목을 추가한다.
        Long reuseId = last.filter(a -> a.getStatus() != AnalysisStatus.CONFIRMED)
                .map(DiscussionAnalysis::getAnalysisId).orElse(null);

        AnalysisStore.Begin begin = analysisStore.begin(reuseId, contentRefId, requestedBy);
        try {
            List<String> previousItems = previousItems(history);
            DiscussionAnalyzer.Result result = discussionAnalyzer.analyze(
                    slackDirectory.getCachedChannelName(workspaceId, root.getChannelId()), messages, analyzedUntil,
                    previousItems, LocalDate.now(SlackApiClient.ZONE));
            List<NewItem> items = toNewItems(workspaceId, requestedBy, result.candidates());
            analysisStore.saveResult(begin.analysisId(), items, lastMessageAt);
            log.info("논의 분석 완료: workspaceId={}, analysisId={}, items={}, reused={}", workspaceId,
                    begin.analysisId(), items.size(), begin.reused());
        } catch (RuntimeException e) {
            analysisStore.markFailed(begin);
            log.warn("논의 분석 실패: workspaceId={}, analysisId={}, error={}", workspaceId, begin.analysisId(),
                    e instanceof BusinessException be ? be.getErrorCode() : e.getClass().getSimpleName());
            throw e instanceof BusinessException be ? be : new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
        return getAnalysis(workspaceId, begin.analysisId());
    }

    /**
     * 자동 수집 분석 (AnalysisScheduler). since 이후 갱신되고 논의가 끝난 스레드만 분석한다.
     *
     * @return 분석한 스레드 수
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int collectAndAnalyze(Long workspaceId, LocalDateTime since, int limit) {
        int analyzed = 0;
        for (ContentRefDTO thread : slackContentService.getUpdatedThreads(workspaceId, since)) {
            if (analyzed >= limit) {
                break;
            }
            List<ThreadMessage> messages = slackContentService.getThreadMessages(thread.contentRefId(), null);
            if (!isSettled(messages, LocalDateTime.now(SlackApiClient.ZONE))) {
                continue;
            }
            LocalDateTime range = determineAnalysisRange(
                    analysisRepository.findAllByContentRefIdOrderByAnalysisIdAsc(thread.contentRefId()));
            if (range != null && !lastMessageAt(messages).isAfter(range)) {
                continue;
            }
            try {
                analyzeThread(workspaceId, thread.contentRefId(), null);
                analyzed++;
            } catch (BusinessException e) {
                log.info("자동 분석 건너뜀: workspaceId={}, error={}", workspaceId, e.getErrorCode());
            }
        }
        return analyzed;
    }

    // ===================== 조회 =====================

    /** 검토 대기 목록: 스레드별 최신 분석 중 확정되지 않은 것 */
    public List<AnalysisSummaryDTO> getPendingAnalyses(Long workspaceId) {
        List<DiscussionAnalysis> all = analysisRepository.findByWorkspaceAndStatus(workspaceId,
                EnumSet.allOf(AnalysisStatus.class));
        Map<Long, DiscussionAnalysis> latestByThread = new LinkedHashMap<>();
        all.forEach(a -> latestByThread.putIfAbsent(a.getContentRefId(), a)); // analysisId 내림차순
        List<DiscussionAnalysis> pending = latestByThread.values().stream()
                .filter(a -> a.getStatus() != AnalysisStatus.CONFIRMED)
                .toList();
        Map<Long, ContentRef> threads = contentRefRepository
                .findAllById(pending.stream().map(DiscussionAnalysis::getContentRefId).toList()).stream()
                .collect(Collectors.toMap(ContentRef::getContentRefId, Function.identity()));
        Map<Long, Long> itemCounts = itemRepository
                .findAllByAnalysisIdIn(pending.stream().map(DiscussionAnalysis::getAnalysisId).toList()).stream()
                .collect(Collectors.groupingBy(AnalysisItem::getAnalysisId, Collectors.counting()));
        return pending.stream().map(a -> {
            ContentRef thread = threads.get(a.getContentRefId());
            return new AnalysisSummaryDTO(a.getAnalysisId(), a.getContentRefId(),
                    thread == null ? null : thread.getTitle(), thread == null ? null : preview(thread),
                    a.getStatus(), a.getRequestedBy() == null, itemCounts.getOrDefault(a.getAnalysisId(), 0L),
                    a.getCreatedAt());
        }).toList();
    }

    /** 스레드의 최신 분석. 없으면 빈 값 (UI-04에서 "논의 정리" 진입 시 기존 결과 확인) */
    public Optional<Long> findLatestAnalysisId(Long workspaceId, Long contentRefId) {
        getThreadRoot(workspaceId, contentRefId);
        return analysisRepository.findFirstByContentRefIdOrderByAnalysisIdDesc(contentRefId)
                .map(DiscussionAnalysis::getAnalysisId);
    }

    /** 분석 결과를 항목·근거 발언·스레드 메시지와 함께 */
    public AnalysisResultDTO getAnalysis(Long workspaceId, Long analysisId) {
        DiscussionAnalysis analysis = getAnalysisEntity(workspaceId, analysisId);
        ContentRef root = contentRefRepository.findById(analysis.getContentRefId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<AnalysisItem> items = itemRepository.findAllByAnalysisIdOrderByItemIdAsc(analysisId);
        List<ThreadMessage> messages = slackContentService.getThreadMessages(root.getContentRefId(), null);
        LocalDateTime until = analysis.getAnalyzedUntil();
        List<ThreadMessageResponse> messageResponses = messages.stream()
                .map(m -> new ThreadMessageResponse(m.contentRefId(), m.authorName(),
                        slackDirectory.renderMentions(workspaceId, m.text()), m.createdAt(),
                        until != null && m.createdAt() != null && !m.createdAt().isAfter(until)))
                .toList();
        return new AnalysisResultDTO(analysis.getAnalysisId(), root.getContentRefId(), root.getChannelId(),
                root.getTitle(), preview(root), root.getDeepLink(), analysis.getStatus(),
                analysis.getRequestedBy() == null, until, analysis.getCreatedAt(), toResponses(workspaceId, items),
                messageResponses, analysis.getNotionPageRefId(), analysis.getNotionOutputStatus());
    }

    // ===================== 검토 (SD-08-02) =====================

    @Transactional
    public AnalysisItemResponse updateItem(Long workspaceId, Long itemId, AnalysisItemUpdateRequest request) {
        AnalysisItem item = getItemEntity(workspaceId, itemId);
        DiscussionAnalysis analysis = getEditableAnalysis(workspaceId, item.getAnalysisId());
        validateMembers(analysisWorkspace(analysis), request.suggestedAssigneeId(), request.suggestedParticipantIds());
        item.update(request.content().strip(), request.suggestedAssigneeId(), request.suggestedDueDate(),
                request.suggestedDatetime(), request.suggestedParticipantIds());
        item.markUndetermined(isUndetermined(item.getItemType(), request.suggestedAssigneeId(),
                request.suggestedDueDate(), request.suggestedDatetime()));
        return toResponses(workspaceId, List.of(item)).getFirst();
    }

    /** 항목 추가. 추출된 결정사항이 없을 때 빈 양식으로 직접 작성하는 경우도 여기로 온다. */
    @Transactional
    public AnalysisItemResponse addItem(Long workspaceId, Long analysisId, AnalysisItemRequest request) {
        DiscussionAnalysis analysis = getEditableAnalysis(workspaceId, analysisId);
        validateMembers(analysisWorkspace(analysis), request.suggestedAssigneeId(), request.suggestedParticipantIds());
        AnalysisItem item = new AnalysisItem(analysisId, request.itemType(), request.content().strip(),
                isUndetermined(request.itemType(), request.suggestedAssigneeId(), request.suggestedDueDate(),
                        request.suggestedDatetime()));
        item.update(request.content().strip(), request.suggestedAssigneeId(), request.suggestedDueDate(),
                request.suggestedDatetime(), request.suggestedParticipantIds());
        itemRepository.save(item);
        if (analysis.getStatus() != AnalysisStatus.REVIEWING) {
            analysis.changeStatus(AnalysisStatus.REVIEWING); // NO_CONTENT·FAILED에서 직접 작성
        }
        return toResponses(workspaceId, List.of(item)).getFirst();
    }

    /** 근거 발언과 함께 삭제한다. (FK ON DELETE CASCADE) */
    @Transactional
    public void deleteItem(Long workspaceId, Long itemId) {
        AnalysisItem item = getItemEntity(workspaceId, itemId);
        getEditableAnalysis(workspaceId, item.getAnalysisId());
        evidenceRepository.deleteAllInBatch(evidenceRepository.findAllByItemIdIn(List.of(itemId)));
        itemRepository.delete(item);
    }

    /** 같은 스레드의 메시지를 근거로 연결한다. */
    @Transactional
    public AnalysisEvidenceDTO linkEvidence(Long workspaceId, Long itemId, Long contentRefId) {
        AnalysisItem item = getItemEntity(workspaceId, itemId);
        DiscussionAnalysis analysis = getEditableAnalysis(workspaceId, item.getAnalysisId());
        ContentRef message = contentRefRepository.findById(contentRefId)
                .filter(m -> m.getContentRefId().equals(analysis.getContentRefId())
                        || analysis.getContentRefId().equals(m.getParentRefId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_NOT_IN_THREAD));
        AnalysisEvidence evidence = evidenceRepository.findAllByItemIdIn(List.of(itemId)).stream()
                .filter(e -> e.getContentRefId().equals(contentRefId)).findFirst()
                .orElseGet(() -> evidenceRepository.save(new AnalysisEvidence(itemId, contentRefId)));
        return toEvidence(evidence, message);
    }

    @Transactional
    public void unlinkEvidence(Long workspaceId, Long evidenceId) {
        AnalysisEvidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        AnalysisItem item = getItemEntity(workspaceId, evidence.getItemId());
        getEditableAnalysis(workspaceId, item.getAnalysisId());
        evidenceRepository.delete(evidence);
    }

    /** 검토를 마치고 확정한다. 확정 후에만 Notion·Task·Calendar로 연계할 수 있다. (B-08) */
    @Transactional
    public void confirmAnalysis(Long workspaceId, Long analysisId) {
        DiscussionAnalysis analysis = getEditableAnalysis(workspaceId, analysisId);
        if (itemRepository.countByAnalysisId(analysisId) == 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT); // 빈 분석은 확정할 수 없다.
        }
        analysis.changeStatus(AnalysisStatus.CONFIRMED);
    }

    /** 확정 전 취소: 분석 결과를 버린다. 외부 서비스에는 아무것도 반영되지 않는다. */
    @Transactional
    public void discardAnalysis(Long workspaceId, Long analysisId) {
        DiscussionAnalysis analysis = getEditableAnalysis(workspaceId, analysisId);
        List<AnalysisItem> items = itemRepository.findAllByAnalysisIdOrderByItemIdAsc(analysisId);
        evidenceRepository.deleteAllInBatch(evidenceRepository.findAllByItemIdIn(
                items.stream().map(AnalysisItem::getItemId).toList()));
        itemRepository.deleteAllInBatch(items);
        analysisRepository.delete(analysis);
    }

    // ===================== 다른 파트 제공 (docs/shared-interfaces.md [결정 8]) =====================

    public AnalysisItemDTO getItemSummary(Long itemId) {
        AnalysisItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_ITEM_NOT_FOUND));
        List<Long> evidence = evidenceRepository.findAllByItemIdIn(List.of(itemId)).stream()
                .map(AnalysisEvidence::getContentRefId).toList();
        return new AnalysisItemDTO(item.getItemId(), item.getItemType(), item.getContent(), evidence);
    }

    // ===================== B 내부 (분석 결과 실행 연계, B-08) =====================

    public DiscussionAnalysis getConfirmedAnalysis(Long workspaceId, Long analysisId) {
        DiscussionAnalysis analysis = getAnalysisEntity(workspaceId, analysisId);
        if (analysis.getStatus() != AnalysisStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_CONFIRMED);
        }
        return analysis;
    }

    public AnalysisItem getItemEntity(Long workspaceId, Long itemId) {
        AnalysisItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_ITEM_NOT_FOUND));
        getAnalysisEntity(workspaceId, item.getAnalysisId());
        return item;
    }

    public List<AnalysisItemResponse> toResponses(Long workspaceId, List<AnalysisItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AnalysisEvidence>> evidences = evidenceRepository
                .findAllByItemIdIn(items.stream().map(AnalysisItem::getItemId).toList()).stream()
                .collect(Collectors.groupingBy(AnalysisEvidence::getItemId));
        Map<Long, ContentRef> messages = contentRefRepository
                .findAllById(evidences.values().stream().flatMap(List::stream)
                        .map(AnalysisEvidence::getContentRefId).distinct().toList()).stream()
                .collect(Collectors.toMap(ContentRef::getContentRefId, Function.identity()));
        Map<Long, String> names = userService.getUsers(items.stream().map(AnalysisItem::getSuggestedAssigneeId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(UserSummary::userId, UserSummary::name));
        return items.stream().map(item -> new AnalysisItemResponse(item.getItemId(), item.getItemType(),
                item.getContent(), item.isUndetermined(), item.getSuggestedAssigneeId(),
                names.get(item.getSuggestedAssigneeId()), item.getSuggestedDueDate(), item.getSuggestedDatetime(),
                item.getSuggestedParticipantIds(), item.getOutputStatus(), item.getOutputTargetId(),
                evidences.getOrDefault(item.getItemId(), List.of()).stream()
                        .filter(e -> messages.containsKey(e.getContentRefId()))
                        .sorted(Comparator.comparing(e -> messages.get(e.getContentRefId()).getCreatedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(e -> toEvidence(e, messages.get(e.getContentRefId())))
                        .toList()))
                .toList();
    }

    // ===================== 내부 =====================

    /** 명세서 isSettled: 답글이 1개 이상이고 마지막 메시지 후 30분 동안 조용하면 끝난 논의로 본다. */
    static boolean isSettled(List<ThreadMessage> messages, LocalDateTime now) {
        if (messages.size() < 2) {
            return false;
        }
        return lastMessageAt(messages).plus(SETTLE_TIME).isBefore(now);
    }

    /** 명세서 determineAnalysisRange: 실패하지 않은 이전 분석 중 가장 늦은 analyzedUntil */
    static LocalDateTime determineAnalysisRange(List<DiscussionAnalysis> history) {
        return history.stream()
                .filter(a -> ANALYZED.contains(a.getStatus()))
                .map(DiscussionAnalysis::getAnalyzedUntil)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    static boolean isUndetermined(ItemType type, Long assigneeId, LocalDate dueDate, LocalDateTime datetime) {
        return switch (type) {
            case FOLLOW_UP -> assigneeId == null || dueDate == null;
            case MEETING_PROPOSAL -> datetime == null;
            default -> false;
        };
    }

    private static LocalDateTime lastMessageAt(List<ThreadMessage> messages) {
        return messages.stream()
                .flatMap(m -> Stream.of(m.createdAt(), m.lastModifiedAt()))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(LocalDateTime.MIN);
    }

    private List<String> previousItems(List<DiscussionAnalysis> history) {
        List<Long> ids = history.stream().filter(a -> ANALYZED.contains(a.getStatus()))
                .map(DiscussionAnalysis::getAnalysisId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return itemRepository.findAllByAnalysisIdIn(ids).stream()
                .map(i -> i.getItemType() + ": " + i.getContent())
                .toList();
    }

    /** Slack 사용자 → 같은 이메일의 팀원. 찾지 못한 담당자는 비워 두고 미정으로 표시한다. */
    private List<NewItem> toNewItems(Long workspaceId, Long requestedBy, List<Candidate> candidates) {
        Set<String> slackIds = new HashSet<>();
        candidates.forEach(c -> {
            if (c.assigneeSlackId() != null) {
                slackIds.add(c.assigneeSlackId());
            }
            slackIds.addAll(c.participantSlackIds());
        });
        Map<String, Long> members = slackIds.isEmpty() ? Map.of() : mapMembers(workspaceId, requestedBy, slackIds);
        List<NewItem> items = new ArrayList<>();
        for (Candidate c : candidates) {
            Long assignee = c.assigneeSlackId() == null ? null : members.get(c.assigneeSlackId());
            List<Long> participants = c.participantSlackIds().stream().map(members::get).filter(Objects::nonNull)
                    .distinct().toList();
            items.add(new NewItem(c.itemType(), c.content(),
                    isUndetermined(c.itemType(), assignee, c.dueDate(), c.datetime()),
                    c.itemType() == ItemType.FOLLOW_UP ? assignee : null, c.dueDate(), c.datetime(), participants,
                    c.evidenceContentRefIds()));
        }
        return items;
    }

    private Map<String, Long> mapMembers(Long workspaceId, Long requestedBy, Collection<String> slackIds) {
        try {
            Optional<String> token = teamTokenResolver.findTokenFor(workspaceId, Provider.SLACK, null, requestedBy);
            if (token.isEmpty()) {
                return Map.of();
            }
            Map<String, SlackUser> users = slackDirectory.getUsers(workspaceId, token.get(), slackIds);
            return slackDirectory.matchMembers(workspaceId, users.values());
        } catch (BusinessException e) {
            log.info("담당자 매핑 생략: workspaceId={}, error={}", workspaceId, e.getErrorCode());
            return Map.of();
        }
    }

    private void validateMembers(Long workspaceId, Long assigneeId, List<Long> participantIds) {
        List<Long> ids = new ArrayList<>();
        if (assigneeId != null) {
            ids.add(assigneeId);
        }
        if (participantIds != null) {
            ids.addAll(participantIds);
        }
        for (Long id : ids) {
            if (!workspaceMemberService.isMember(workspaceId, id)) {
                throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
            }
        }
    }

    private ContentRef getThreadRoot(Long workspaceId, Long contentRefId) {
        ContentRef root = contentRefRepository.findById(contentRefId)
                .filter(r -> r.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (root.getProvider() != Provider.SLACK || root.getParentRefId() != null) {
            throw new BusinessException(ErrorCode.ANALYSIS_TARGET_INVALID);
        }
        return root;
    }

    private DiscussionAnalysis getAnalysisEntity(Long workspaceId, Long analysisId) {
        DiscussionAnalysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
        if (!workspaceId.equals(analysisWorkspace(analysis))) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND);
        }
        return analysis;
    }

    private DiscussionAnalysis getEditableAnalysis(Long workspaceId, Long analysisId) {
        DiscussionAnalysis analysis = getAnalysisEntity(workspaceId, analysisId);
        if (analysis.getStatus() == AnalysisStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_CONFIRMED);
        }
        if (analysis.getStatus() == AnalysisStatus.ANALYZING) {
            throw new BusinessException(ErrorCode.ANALYSIS_IN_PROGRESS);
        }
        return analysis;
    }

    private Long analysisWorkspace(DiscussionAnalysis analysis) {
        return contentRefRepository.findById(analysis.getContentRefId()).map(ContentRef::getWorkspaceId).orElse(null);
    }

    private AnalysisEvidenceDTO toEvidence(AnalysisEvidence evidence, ContentRef message) {
        String text = SlackMessageFormat.parse(message.isOriginDeleted() ? message.getSnapshot() : message.getContent())
                .text();
        return new AnalysisEvidenceDTO(evidence.getEvidenceId(), evidence.getItemId(), message.getContentRefId(),
                message.getAuthorName(), slackDirectory.renderMentions(message.getWorkspaceId(), text),
                message.getCreatedAt(), message.getDeepLink(), message.isOriginDeleted());
    }

    private String preview(ContentRef ref) {
        String text = SlackMessageFormat.parse(ref.isOriginDeleted() ? ref.getSnapshot() : ref.getContent()).text();
        String rendered = slackDirectory.renderMentions(ref.getWorkspaceId(), text).replace('\n', ' ');
        return rendered.length() <= PREVIEW_LENGTH ? rendered : rendered.substring(0, PREVIEW_LENGTH) + "…";
    }
}
