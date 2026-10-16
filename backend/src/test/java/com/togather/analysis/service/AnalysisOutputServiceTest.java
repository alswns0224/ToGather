package com.togather.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.togather.analysis.dto.AnalysisItemResponse;
import com.togather.analysis.dto.AnalysisResultDTO;
import com.togather.analysis.dto.DocumentDraftDTO;
import com.togather.analysis.dto.FollowUpTaskRequest;
import com.togather.analysis.dto.MeetingDraftDTO;
import com.togather.analysis.dto.NotionOutputRequest;
import com.togather.analysis.service.DiscussionAnalyzer.AiItem;
import com.togather.analysis.service.DiscussionAnalyzer.AiResult;
import com.togather.common.ai.GeminiClient;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.OutputStatus;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.service.CalendarContentService;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.service.NotionContentService;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.integration.service.IntegrationService;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class AnalysisOutputServiceTest extends IntegrationTestSupport {

    @MockitoBean
    private GeminiClient geminiClient;
    @MockitoBean
    private SlackApiClient slackApiClient;
    @MockitoBean
    private IntegrationService integrationService;
    @MockitoBean
    private NotionContentService notionContentService;
    @MockitoBean
    private CalendarContentService calendarContentService;

    @Autowired
    private AnalysisService analysisService;
    @Autowired
    private AnalysisOutputService outputService;
    @Autowired
    private ContentRefService contentRefService;
    @Autowired
    private TaskGateway taskGateway;

    private Long userId;
    private Long memberId;
    private Long workspaceId;
    private AnalysisResultDTO analysis;

    @BeforeEach
    void setUp() {
        userId = createUser("철준", "cj@test.com");
        memberId = createUser("민준", "mj@test.com");
        workspaceId = createWorkspace(userId);
        addMember(workspaceId, memberId, "MEMBER");
        LocalDateTime t0 = LocalDateTime.now(SlackApiClient.ZONE).minusHours(2).withNano(0);
        ContentRefDTO root = slack("1.0", null, "U1", "철준", "회의 언제 할까요?", t0);
        slack("1.1", root.contentRefId(), "U2", "민준", "목요일 3시 어때요? 자료는 제가 할게요", t0.plusMinutes(1));

        when(integrationService.findAccessToken(eq(workspaceId), any(), eq(Provider.SLACK)))
                .thenReturn(Optional.of("token"));
        when(slackApiClient.getUser("token", "U1")).thenReturn(new SlackUser("U1", "철준", "cj@test.com"));
        when(slackApiClient.getUser("token", "U2")).thenReturn(new SlackUser("U2", "민준", "mj@test.com"));
        when(geminiClient.generateJson(anyString(), eq(AiResult.class))).thenReturn(new AiResult(List.of(
                new AiItem("DECISION", "회의는 목요일 3시", List.of("m2"), null, null, null, null),
                new AiItem("FOLLOW_UP", "자료 준비", List.of("m2"), "U2", "2026-10-15", null, null),
                new AiItem("MEETING_PROPOSAL", "목요일 3시 회의", List.of("m2"), null, null, "2026-10-15T15:00",
                        List.of())), true));
        analysis = analysisService.analyzeThread(workspaceId, root.contentRefId(), userId);
    }

    @Test
    void 확정하기_전에는_연계할_수_없다() {
        assertThatThrownBy(() -> outputService.previewNotionDocument(workspaceId, analysis.analysisId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_CONFIRMED));
    }

    @Test
    void Notion_문서_초안을_만들고_생성_결과를_기록한다() {
        analysisService.confirmAnalysis(workspaceId, analysis.analysisId());

        DocumentDraftDTO draft = outputService.previewNotionDocument(workspaceId, analysis.analysisId());
        assertThat(draft.body()).contains("## 결정사항", "- 회의는 목요일 3시", "[ ] 자료 준비 (담당: 민준, 마감: 2026-10-15)",
                "> 민준: 목요일 3시 어때요?");

        ContentRefDTO page = contentRefService.upsert(new ContentRefUpsert(workspaceId, null, Provider.NOTION,
                ContentType.PAGE, "p1", "db1", null, draft.title(), draft.body(), "https://notion.so/p1",
                LocalDateTime.now()));
        when(notionContentService.createContent(eq(workspaceId), eq(userId), any(
                com.togather.content.notion.dto.NotionPageRequest.class))).thenReturn(page);
        outputService.createNotionDocument(workspaceId, userId, analysis.analysisId(),
                new NotionOutputRequest(draft.title(), draft.body(), null));

        AnalysisResultDTO result = analysisService.getAnalysis(workspaceId, analysis.analysisId());
        assertThat(result.notionOutputStatus()).isEqualTo(OutputStatus.SUCCESS);
        assertThat(result.notionPageRefId()).isEqualTo(page.contentRefId());
        assertThatThrownBy(() -> outputService.createNotionDocument(workspaceId, userId, analysis.analysisId(),
                new NotionOutputRequest("다시", "본문", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_OUTPUT_ALREADY_DONE));
    }

    @Test
    void 실패한_단계는_FAILED로_기록되고_다시_시도하면_Task가_생성된다() {
        analysisService.confirmAnalysis(workspaceId, analysis.analysisId());
        AnalysisItemResponse followUp = item(ItemType.FOLLOW_UP);
        Long stranger = createUser("외부인", "x@test.com");

        // 팀원이 아닌 담당자 → C의 TaskService가 거부 → 실패로 기록
        assertThatThrownBy(() -> outputService.createFollowUpTask(workspaceId, followUp.itemId(),
                new FollowUpTaskRequest("자료 준비", null, stranger, LocalDate.of(2026, 10, 15))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
        assertThat(item(ItemType.FOLLOW_UP).outputStatus()).isEqualTo(OutputStatus.FAILED);

        // 다시 시도 → C의 Task 생성, 근거 결정사항 연결
        var result = outputService.createFollowUpTask(workspaceId, followUp.itemId(),
                new FollowUpTaskRequest("자료 준비", "발표 자료", memberId, LocalDate.of(2026, 10, 15)));
        assertThat(item(ItemType.FOLLOW_UP).outputStatus()).isEqualTo(OutputStatus.SUCCESS);
        assertThat(jdbc.queryForObject("select origin_analysis_item_id from task where task_id = ?", Long.class,
                result.targetId())).isEqualTo(followUp.itemId());
        assertThat(taskGateway.getMyTasks(memberId)).extracting(TaskGateway.MyTask::title).contains("자료 준비");

        // 이미 만든 Task는 중복 생성하지 않는다.
        assertThatThrownBy(() -> outputService.createFollowUpTask(workspaceId, followUp.itemId(),
                new FollowUpTaskRequest("자료 준비", null, memberId, LocalDate.of(2026, 10, 15))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_OUTPUT_ALREADY_DONE));
    }

    @Test
    void 회의_제안은_스레드_참여자로_초안을_만들고_팀_캘린더에_등록한다() {
        analysisService.confirmAnalysis(workspaceId, analysis.analysisId());
        AnalysisItemResponse meeting = item(ItemType.MEETING_PROPOSAL);

        MeetingDraftDTO draft = outputService.previewMeetingSchedule(workspaceId, userId, meeting.itemId());
        assertThat(draft.startAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 15, 0));
        assertThat(draft.participantIds()).containsExactlyInAnyOrder(userId, memberId);

        when(calendarContentService.createContent(eq(workspaceId), eq(userId), any())).thenReturn(
                new ContentRefDTO(77L, Provider.GOOGLE_CALENDAR, ContentType.EVENT, "e1", draft.title(), null,
                        "https://calendar/e1", null, null, 0, false));
        outputService.registerMeetingSchedule(workspaceId, userId, meeting.itemId(), draft);

        AnalysisItemResponse done = item(ItemType.MEETING_PROPOSAL);
        assertThat(done.outputStatus()).isEqualTo(OutputStatus.SUCCESS);
        assertThat(done.outputTargetId()).isEqualTo(77L);
    }

    private AnalysisItemResponse item(ItemType type) {
        return analysisService.getAnalysis(workspaceId, analysis.analysisId()).items().stream()
                .filter(i -> i.itemType() == type).findFirst().orElseThrow();
    }

    private ContentRefDTO slack(String ts, Long parent, String author, String name, String text, LocalDateTime at) {
        return contentRefService.upsert(new ContentRefUpsert(workspaceId, null, Provider.SLACK, ContentType.MESSAGE,
                "C1:" + ts + ":" + workspaceId, "C1", parent, "#general", text, null, at, at, author, name));
    }
}
