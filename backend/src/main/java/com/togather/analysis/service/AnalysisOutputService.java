package com.togather.analysis.service;

import com.togather.analysis.domain.AnalysisItem;
import com.togather.analysis.domain.DiscussionAnalysis;
import com.togather.analysis.dto.AnalysisEvidenceDTO;
import com.togather.analysis.dto.AnalysisItemResponse;
import com.togather.analysis.dto.DocumentDraftDTO;
import com.togather.analysis.dto.FollowUpTaskRequest;
import com.togather.analysis.dto.MeetingDraftDTO;
import com.togather.analysis.dto.NotionOutputRequest;
import com.togather.analysis.dto.OutputResultResponse;
import com.togather.analysis.repository.AnalysisItemRepository;
import com.togather.analysis.service.TaskGateway.FollowUpTask;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.OutputStatus;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.dto.NotionPageRequest;
import com.togather.content.notion.service.NotionContentService;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.slack.service.SlackContentService;
import com.togather.content.slack.service.SlackContentService.ThreadMessage;
import com.togather.content.slack.service.SlackDirectory;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.meeting.dto.MeetingCreateRequest;
import com.togather.meeting.dto.MeetingDetailResponse;
import com.togather.meeting.service.MeetingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import com.togather.content.service.TeamTokenResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정된 분석 결과의 실행 연계 (UC-08-03 ~ 05, SD-08-03 ~ 05).
 * 단계(Notion 문서, 항목별 Task, 항목별 Calendar 일정)마다 성공·실패를 기록하고, 실패한 단계만 다시 시도할 수 있다.
 * 이미 성공한 단계는 다시 만들지 않는다. (중복 생성 방지)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AnalysisOutputService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<ItemType> SECTION_ORDER = List.of(
            ItemType.DECISION, ItemType.FOLLOW_UP, ItemType.OPEN_ISSUE, ItemType.MEETING_PROPOSAL);
    private static final Map<ItemType, String> SECTION_TITLE = Map.of(
            ItemType.DECISION, "결정사항", ItemType.FOLLOW_UP, "후속 작업",
            ItemType.OPEN_ISSUE, "미결 사항", ItemType.MEETING_PROPOSAL, "회의 제안");

    private final AnalysisService analysisService;
    private final AnalysisStore analysisStore;
    private final AnalysisItemRepository itemRepository;
    private final NotionContentService notionContentService;
    private final TaskGateway taskGateway;
    private final ContentRefRepository contentRefRepository;
    private final SlackContentService slackContentService;
    private final SlackDirectory slackDirectory;
    private final TeamTokenResolver teamTokenResolver;
    private final MeetingService meetingService;

    // ===================== Notion 문서 (SD-08-03) =====================

    /** 명세서 previewNotionDocument / buildDocumentDraft */
    public DocumentDraftDTO previewNotionDocument(Long workspaceId, Long analysisId) {
        DiscussionAnalysis analysis = analysisService.getConfirmedAnalysis(workspaceId, analysisId);
        ContentRef thread = contentRefRepository.findById(analysis.getContentRefId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<AnalysisItemResponse> items = analysisService.toResponses(workspaceId,
                itemRepository.findAllByAnalysisIdOrderByItemIdAsc(analysisId));
        String date = DATE.format(analysis.getUpdatedAt() != null ? analysis.getUpdatedAt().toLocalDate() : LocalDate.now());
        String title = Objects.requireNonNullElse(thread.getTitle(), "Slack") + " 논의 정리 (" + date + ")";
        return new DocumentDraftDTO(title, buildDocumentBody(items, thread.getDeepLink()));
    }

    /** Notion 문서를 만들고 결과를 기록한다. 실패하면 FAILED로 기록하고 예외를 그대로 던진다. */
    public OutputResultResponse createNotionDocument(Long workspaceId, Long userId, Long analysisId,
                                                     NotionOutputRequest request) {
        DiscussionAnalysis analysis = analysisService.getConfirmedAnalysis(workspaceId, analysisId);
        if (analysis.getNotionOutputStatus() == OutputStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.ANALYSIS_OUTPUT_ALREADY_DONE);
        }
        ContentRefDTO page = record(() -> notionContentService.createContent(workspaceId, userId,
                        new NotionPageRequest(request.title(), request.body(), request.databaseId())),
                failed -> analysisStore.recordNotionOutput(analysisId, null, OutputStatus.FAILED));
        analysisStore.recordNotionOutput(analysisId, page.contentRefId(), OutputStatus.SUCCESS);
        return new OutputResultResponse(OutputStatus.SUCCESS, page.contentRefId(), page.deepLink());
    }

    // ===================== 후속 작업 → Task (SD-08-04) =====================

    public List<AnalysisItemResponse> getFollowUpItems(Long workspaceId, Long analysisId) {
        analysisService.getConfirmedAnalysis(workspaceId, analysisId);
        return analysisService.toResponses(workspaceId,
                itemRepository.findAllByAnalysisIdAndItemTypeOrderByItemIdAsc(analysisId, ItemType.FOLLOW_UP));
    }

    public OutputResultResponse createFollowUpTask(Long workspaceId, Long itemId, FollowUpTaskRequest request) {
        AnalysisItem item = getOutputItem(workspaceId, itemId, ItemType.FOLLOW_UP);
        Long taskId = record(() -> taskGateway.createTask(workspaceId, new FollowUpTask(request.title(),
                        request.description(), request.assigneeId(), request.dueDate(), itemId)),
                failed -> analysisStore.recordItemOutput(itemId, null, OutputStatus.FAILED));
        analysisStore.recordItemOutput(item.getItemId(), taskId, OutputStatus.SUCCESS);
        return new OutputResultResponse(OutputStatus.SUCCESS, taskId, null);
    }

    // ===================== 회의 제안 → 회의 (SD-08-05) =====================

    /** 참여자를 정하지 않았으면 스레드 참여자(팀원으로 연결된 사람)로 채운다. */
    public MeetingDraftDTO previewMeetingSchedule(Long workspaceId, Long userId, Long itemId) {
        AnalysisItem item = getOutputItem(workspaceId, itemId, ItemType.MEETING_PROPOSAL);
        DiscussionAnalysis analysis = analysisService.getConfirmedAnalysis(workspaceId, item.getAnalysisId());
        List<Long> participants = item.getSuggestedParticipantIds() == null || item.getSuggestedParticipantIds().isEmpty()
                ? threadParticipants(workspaceId, userId, analysis.getContentRefId())
                : item.getSuggestedParticipantIds();
        ContentRef thread = contentRefRepository.findById(analysis.getContentRefId()).orElse(null);
        String description = item.getContent()
                + (thread != null && thread.getDeepLink() != null ? "\n\n논의 스레드: " + thread.getDeepLink() : "");
        return new MeetingDraftDTO(truncate(item.getContent(), 200), item.getSuggestedDatetime(),
                item.getSuggestedDatetime() == null ? null : item.getSuggestedDatetime().plusHours(1),
                participants, description);
    }

    /**
     * 회의 제안으로 ToGather 회의를 만든다. (설계 C-04-03 registerMeetingSchedule: 회의를 생성·확정)
     * <ul>
     *   <li>일시가 있으면 그 시간으로 바로 확정한다. 회의 확정과 같이 팀 캘린더에 Google Meet 링크와 함께 등록된다.</li>
     *   <li>일시가 없으면 "일정 조율 중" 회의로 만든다. 참여자가 가용 시간을 입력하고 추천 시간으로 확정하면 된다.
     *       후보 기간은 내일 {@value #PROPOSAL_DAY_START}시 ~ {@value #PROPOSAL_PERIOD_DAYS}일 뒤 {@value #PROPOSAL_DAY_END}시.</li>
     * </ul>
     * outputTargetId = 회의 ID
     */
    public OutputResultResponse registerMeetingSchedule(Long workspaceId, Long userId, Long itemId,
                                                        MeetingDraftDTO draft) {
        getOutputItem(workspaceId, itemId, ItemType.MEETING_PROPOSAL);
        Long meetingId = record(() -> createMeetingFromProposal(workspaceId, userId, draft),
                failed -> analysisStore.recordItemOutput(itemId, null, OutputStatus.FAILED));
        analysisStore.recordItemOutput(itemId, meetingId, OutputStatus.SUCCESS);
        return new OutputResultResponse(OutputStatus.SUCCESS, meetingId, null);
    }

    static final int PROPOSAL_DAY_START = 9;
    static final int PROPOSAL_DAY_END = 22;
    static final int PROPOSAL_PERIOD_DAYS = 7;
    static final int DEFAULT_MEETING_MINUTES = 60;
    private static final int MIN_MEETING_MINUTES = 15;
    private static final int MAX_MEETING_MINUTES = 480;

    private Long createMeetingFromProposal(Long workspaceId, Long userId, MeetingDraftDTO draft) {
        String title = draft.title().strip();
        List<Long> participants = draft.participantIds() == null ? List.of() : draft.participantIds();
        if (draft.startAt() != null) {
            LocalDateTime start = draft.startAt();
            long minutes = draft.endAt() != null && draft.endAt().isAfter(start)
                    ? java.time.Duration.between(start, draft.endAt()).toMinutes() : DEFAULT_MEETING_MINUTES;
            int duration = (int) Math.max(MIN_MEETING_MINUTES, Math.min(MAX_MEETING_MINUTES, minutes));
            // 정한 시간 하나만 후보 기간으로 두고 바로 확정한다. (확정하면 캘린더 등록·Meet 링크 생성)
            MeetingDetailResponse meeting = meetingService.createMeeting(workspaceId, userId, new MeetingCreateRequest(
                    title, duration, start, start.plusMinutes(duration), participants, null, List.of()));
            meetingService.confirmMeeting(workspaceId, meeting.meetingId(), userId, start, true);
            return meeting.meetingId();
        }
        LocalDate tomorrow = LocalDate.now(SlackApiClient.ZONE).plusDays(1);
        return meetingService.createMeeting(workspaceId, userId, new MeetingCreateRequest(title, DEFAULT_MEETING_MINUTES,
                tomorrow.atTime(PROPOSAL_DAY_START, 0), tomorrow.plusDays(PROPOSAL_PERIOD_DAYS).atTime(PROPOSAL_DAY_END, 0),
                participants, null, List.of())).meetingId();
    }

    // ===================== 내부 =====================

    static String buildDocumentBody(List<AnalysisItemResponse> items, String threadLink) {
        StringBuilder sb = new StringBuilder();
        for (ItemType type : SECTION_ORDER) {
            List<AnalysisItemResponse> section = items.stream().filter(i -> i.itemType() == type).toList();
            if (section.isEmpty()) {
                continue;
            }
            sb.append("## ").append(SECTION_TITLE.get(type)).append('\n');
            for (AnalysisItemResponse item : section) {
                String content = item.content().replace('\n', ' ');
                switch (type) {
                    case FOLLOW_UP -> sb.append("[ ] ").append(content).append(" (담당: ")
                            .append(Objects.requireNonNullElse(item.suggestedAssigneeName(), "미정"))
                            .append(", 마감: ")
                            .append(item.suggestedDueDate() == null ? "미정" : DATE.format(item.suggestedDueDate()))
                            .append(")\n");
                    case MEETING_PROPOSAL -> sb.append("- ").append(content).append(" (일시: ")
                            .append(item.suggestedDatetime() == null ? "미정"
                                    : DATE_TIME.format(item.suggestedDatetime()))
                            .append(")\n");
                    default -> sb.append("- ").append(content).append('\n');
                }
                for (AnalysisEvidenceDTO evidence : item.evidences()) {
                    sb.append("> ").append(Objects.requireNonNullElse(evidence.authorName(), "?")).append(": ")
                            .append(truncate(evidence.text().replace('\n', ' '), 200)).append('\n');
                }
            }
            sb.append('\n');
        }
        if (threadLink != null) {
            sb.append("원본 Slack 스레드: ").append(threadLink).append('\n');
        }
        return sb.toString().strip();
    }

    private AnalysisItem getOutputItem(Long workspaceId, Long itemId, ItemType type) {
        AnalysisItem item = analysisService.getItemEntity(workspaceId, itemId);
        if (item.getItemType() != type) {
            throw new BusinessException(ErrorCode.ANALYSIS_ITEM_TYPE_MISMATCH);
        }
        analysisService.getConfirmedAnalysis(workspaceId, item.getAnalysisId());
        if (item.getOutputStatus() == OutputStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.ANALYSIS_OUTPUT_ALREADY_DONE);
        }
        return item;
    }

    private List<Long> threadParticipants(Long workspaceId, Long userId, Long threadId) {
        List<String> slackIds = slackContentService.getThreadMessages(threadId, null).stream()
                .map(ThreadMessage::authorExternalId).filter(Objects::nonNull).distinct().toList();
        try {
            Optional<String> token = teamTokenResolver.findTokenFor(workspaceId, Provider.SLACK, null, userId);
            if (token.isEmpty() || slackIds.isEmpty()) {
                return List.of();
            }
            return slackDirectory.matchMembers(workspaceId,
                    slackDirectory.getUsers(workspaceId, token.get(), slackIds).values()).values().stream()
                    .distinct().toList();
        } catch (BusinessException e) {
            return List.of();
        }
    }

    /** 외부 연계를 실행하고, 실패하면 실패를 기록한 뒤 예외를 그대로 던진다. */
    private static <T> T record(Supplier<T> action, java.util.function.Consumer<RuntimeException> onFailure) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            onFailure.accept(e);
            throw e;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
