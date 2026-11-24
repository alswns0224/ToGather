package com.togather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.togather.analysis.dto.AnalysisItemResponse;
import com.togather.analysis.dto.AnalysisItemUpdateRequest;
import com.togather.analysis.dto.AnalysisResultDTO;
import com.togather.analysis.dto.DocumentDraftDTO;
import com.togather.analysis.dto.FollowUpTaskRequest;
import com.togather.analysis.dto.MeetingDraftDTO;
import com.togather.analysis.dto.NotionOutputRequest;
import com.togather.analysis.dto.OutputResultResponse;
import com.togather.analysis.service.AnalysisOutputService;
import com.togather.analysis.service.AnalysisService;
import com.togather.analysis.service.DiscussionAnalyzer;
import com.togather.assistant.dto.DetectResultResponse;
import com.togather.assistant.dto.MyTodoListDTO;
import com.togather.assistant.dto.SuggestionDTO;
import com.togather.assistant.dto.SuggestionUpdateRequest;
import com.togather.assistant.service.SuggestionService;
import com.togather.common.ai.GeminiClient;
import com.togather.common.domain.DateRange;
import com.togather.common.enums.AnalysisStatus;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.MeetingStatus;
import com.togather.common.enums.OutputStatus;
import com.togather.common.enums.PersonalTaskStatus;
import com.togather.common.enums.Provider;
import com.togather.common.enums.SlotPreference;
import com.togather.common.enums.SuggestionStatus;
import com.togather.common.enums.SuggestionType;
import com.togather.common.enums.TaskStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.dto.CalendarEventResponse;
import com.togather.content.calendar.service.CalendarContentService;
import com.togather.content.drive.service.DriveContentService;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.gmail.service.GmailContentService;
import com.togather.content.notion.dto.NotionPageRequest;
import com.togather.content.notion.service.NotionContentService;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.integration.service.IntegrationService;
import com.togather.meeting.dto.AvailabilityResponse;
import com.togather.meeting.dto.AvailabilitySaveRequest;
import com.togather.meeting.dto.AvailabilitySaveRequest.SlotInput;
import com.togather.meeting.dto.MeetingCreateRequest;
import com.togather.meeting.dto.MeetingDetailResponse;
import com.togather.meeting.dto.TimeCandidateResponse;
import com.togather.meeting.service.MeetingService;
import com.togather.search.client.EmbeddingClient;
import com.togather.search.client.VectorStore;
import com.togather.search.client.VectorStore.VectorHit;
import com.togather.search.dto.SearchResponse;
import com.togather.search.service.SearchService;
import com.togather.support.IntegrationTestSupport;
import com.togather.task.dto.DeliverableInput;
import com.togather.task.dto.TaskContext;
import com.togather.task.dto.TaskDeliverableSummary;
import com.togather.task.dto.TaskSummary;
import com.togather.task.service.TaskContextService;
import com.togather.task.service.TaskResourceService;
import com.togather.task.service.TaskService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 최종발표 시연 시나리오(ToGather_최종발표_시나리오.md 3장)를 처음부터 끝까지 따라간다.
 *
 * <p>"ToGather 팀이 최종발표를 준비하는 상황": Slack 논의 → AI 논의 정리 → 검토·확정 → Notion 문서·Task·회의 일정 생성
 * → Task 맥락과 산출물 → 회의 추천·확정 → 통합 검색 → Gmail로 온 개인 일정·할 일까지.
 *
 * <p>Slack·Gemini·Notion·Google(Calendar·Drive·Gmail)·검색 엔진(BGE-M3·Chroma)은 가짜 응답으로 바꾸고,
 * 우리 서버의 서비스·DB(내장 PostgreSQL, 실제 마이그레이션)는 실제로 동작시킨다.
 * 그래서 이 테스트가 통과해도 실제 외부 계정 연동까지 확인된 것은 아니다.
 */
@DisplayName("최종발표 시연 시나리오")
@TestMethodOrder(MethodOrderer.DisplayName.class)
class FinalDemoScenarioTest extends IntegrationTestSupport {

    // 외부 서비스 (가짜)
    @MockitoBean private GeminiClient gemini;
    @MockitoBean private SlackApiClient slackApi;
    @MockitoBean private IntegrationService integrations;
    @MockitoBean private NotionContentService notion;
    @MockitoBean private CalendarContentService calendar;
    @MockitoBean private DriveContentService drive;
    @MockitoBean private GmailContentService gmail;
    @MockitoBean private EmbeddingClient embedding;
    @MockitoBean private VectorStore vectorStore;

    // 우리 서버 (실제)
    @Autowired private ContentRefService contents;
    @Autowired private AnalysisService analysisService;
    @Autowired private AnalysisOutputService outputService;
    @Autowired private TaskService taskService;
    @Autowired private TaskContextService taskContextService;
    @Autowired private TaskResourceService taskResources;
    @Autowired private MeetingService meetingService;
    @Autowired private SearchService searchService;
    @Autowired private SuggestionService suggestionService;

    /** 다음 주 화요일 (시연 대사 "다음 주 화요일 오후 3시에 리허설 하자") */
    private static final LocalDate NEXT_TUESDAY =
            LocalDate.now(SlackApiClient.ZONE).plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.TUESDAY));
    private static final LocalDate FRIDAY =
            LocalDate.now(SlackApiClient.ZONE).with(TemporalAdjusters.next(DayOfWeek.FRIDAY));

    private Long cheoljun; // 팀장, 발표자
    private Long minjun;
    private Long chanseo;
    private Long workspaceId;
    private ContentRefDTO threadRoot;

    @BeforeEach
    void 장면0_연동된_워크스페이스와_Slack_스레드를_준비한다() {
        cheoljun = createUser("정철준", "cj@demo.test");
        minjun = createUser("백민준", "mj@demo.test");
        chanseo = createUser("이찬서", "cs@demo.test");
        workspaceId = createWorkspace(cheoljun);
        addMember(workspaceId, minjun, "MEMBER");
        addMember(workspaceId, chanseo, "MEMBER");

        // Slack은 연동된 상태 (토큰과 작성자 정보만 가짜로 준다)
        when(integrations.findAccessToken(eq(workspaceId), any(), eq(Provider.SLACK))).thenReturn(Optional.of("token"));
        when(slackApi.getUser("token", "U1")).thenReturn(new SlackUser("U1", "정철준", "cj@demo.test"));
        when(slackApi.getUser("token", "U2")).thenReturn(new SlackUser("U2", "백민준", "mj@demo.test"));
        when(slackApi.getUser("token", "U3")).thenReturn(new SlackUser("U3", "이찬서", "cs@demo.test"));

        // 장면 1: 시연용 스레드 (시나리오 "시연용 데이터 미리 준비")
        LocalDateTime t0 = LocalDateTime.now(SlackApiClient.ZONE).minusHours(3).withNano(0);
        threadRoot = slack("1.0", null, "U1", "정철준", "최종발표 PPT는 금요일까지 드라이브에 올리자", t0);
        slack("1.1", threadRoot.contentRefId(), "U1", "정철준", "시연 준비는 철준이가, 성능 측정은 민준이가 맡아줘", t0.plusMinutes(1));
        slack("1.2", threadRoot.contentRefId(), "U2", "백민준", "부하 테스트는 k6로 하자", t0.plusMinutes(2));
        slack("1.3", threadRoot.contentRefId(), "U3", "이찬서", "50명 기준이면 충분할 듯", t0.plusMinutes(3));
        slack("1.4", threadRoot.contentRefId(), "U2", "백민준", "다음 주 화요일 오후 3시에 리허설 하자", t0.plusMinutes(4));
        slack("1.5", threadRoot.contentRefId(), "U3", "이찬서", "질의응답 대비 문서는 누가 할지 아직 못 정함", t0.plusMinutes(5));
    }

    @Test
    @DisplayName("장면 1~9 전체 흐름")
    void 시연_전체_흐름() {
        // ===== 장면 2. 논의 분석 (UC-08-01) =====
        when(gemini.generateJson(anyString(), eq(DiscussionAnalyzer.AiResult.class)))
                .thenAnswer(inv -> discussionResult(inv.getArgument(0)));
        AnalysisResultDTO analysis = analysisService.analyzeThread(workspaceId, threadRoot.contentRefId(), cheoljun);

        assertThat(analysis.status()).isEqualTo(AnalysisStatus.REVIEWING);
        assertThat(count(analysis, ItemType.DECISION)).as("결정사항 2~3개").isBetween(2, 3);
        assertThat(count(analysis, ItemType.FOLLOW_UP)).as("후속 작업 2개").isEqualTo(2);
        assertThat(count(analysis, ItemType.OPEN_ISSUE)).as("미결 1개").isEqualTo(1);
        assertThat(count(analysis, ItemType.MEETING_PROPOSAL)).as("회의 제안 1개").isEqualTo(1);
        assertThat(analysis.items()).as("모든 항목에 근거 발언이 연결된다")
                .allSatisfy(item -> assertThat(item.evidences()).isNotEmpty());
        assertThat(analysis.messages()).as("스레드 메시지 6개를 읽었다").hasSize(6);

        // ===== 장면 3. 검토 · 확정 (UC-08-02): 후속 작업 마감일 수정 후 확정 =====
        AnalysisItemResponse perf = followUp(analysis, "성능 측정");
        analysisService.updateItem(workspaceId, perf.itemId(), new AnalysisItemUpdateRequest(
                perf.content(), minjun, FRIDAY, null, null));
        assertThat(followUp(analysisService.getAnalysis(workspaceId, analysis.analysisId()), "성능 측정").suggestedDueDate())
                .isEqualTo(FRIDAY);
        analysisService.confirmAnalysis(workspaceId, analysis.analysisId());
        analysis = analysisService.getAnalysis(workspaceId, analysis.analysisId());
        assertThat(analysis.status()).as("사용자가 확정해야 외부에 반영된다").isEqualTo(AnalysisStatus.CONFIRMED);

        // ===== 장면 4. 실행 연계 결과 (UC-08-03 · 04 · 05, 05-01) =====
        // Notion 결정 문서
        DocumentDraftDTO draft = outputService.previewNotionDocument(workspaceId, analysis.analysisId());
        assertThat(draft.body()).contains("결정사항", "k6");
        ContentRefDTO notionPage = contents.upsert(new ContentRefUpsert(workspaceId, null, Provider.NOTION,
                ContentType.PAGE, "page-final", "db-1", null, draft.title(), draft.body(), "https://notion.so/page-final",
                LocalDateTime.now()));
        when(notion.createContent(eq(workspaceId), eq(cheoljun), any(NotionPageRequest.class))).thenReturn(notionPage);
        outputService.createNotionDocument(workspaceId, cheoljun, analysis.analysisId(),
                new NotionOutputRequest(draft.title(), draft.body(), null));

        // 후속 작업 2개 → Task
        Long perfTaskId = outputService.createFollowUpTask(workspaceId, perf.itemId(),
                new FollowUpTaskRequest("성능 측정", "k6로 50명 부하 테스트", minjun, FRIDAY)).targetId();
        AnalysisItemResponse demo = followUp(analysis, "시연 준비");
        Long demoTaskId = outputService.createFollowUpTask(workspaceId, demo.itemId(),
                new FollowUpTaskRequest("시연 준비", null, cheoljun, FRIDAY)).targetId();

        // 회의 제안 → 팀 캘린더 일정
        AnalysisItemResponse proposal = analysis.items().stream()
                .filter(i -> i.itemType() == ItemType.MEETING_PROPOSAL).findFirst().orElseThrow();
        MeetingDraftDTO meetingDraft = outputService.previewMeetingSchedule(workspaceId, cheoljun, proposal.itemId());
        assertThat(meetingDraft.startAt()).as("'다음 주 화요일 오후 3시'").isEqualTo(NEXT_TUESDAY.atTime(15, 0));
        when(calendar.createContent(eq(workspaceId), eq(cheoljun), any())).thenReturn(calendarRef("rehearsal-event"));
        OutputResultResponse event = outputService.registerMeetingSchedule(workspaceId, cheoljun, proposal.itemId(), meetingDraft);
        assertThat(event.status()).isEqualTo(OutputStatus.SUCCESS);

        analysis = analysisService.getAnalysis(workspaceId, analysis.analysisId());
        assertThat(analysis.notionOutputStatus()).as("한 번의 확정으로 문서가 생성됐다").isEqualTo(OutputStatus.SUCCESS);
        assertThat(analysis.notionPageRefId()).isEqualTo(notionPage.contentRefId());
        assertThat(analysis.items()).filteredOn(i -> i.itemType() == ItemType.FOLLOW_UP)
                .as("후속 작업 2개가 Task로").allSatisfy(i -> assertThat(i.outputStatus()).isEqualTo(OutputStatus.SUCCESS));
        assertThat(taskService.getTasksByWorkspace(workspaceId)).extracting(TaskSummary::title)
                .contains("성능 측정", "시연 준비");

        // ===== 장면 5. Task 맥락 (UC-11-01, 09-01 · 03 · 05 · 06 · 07, 06-02) =====
        TaskContext context = taskContextService.getTaskContext(perfTaskId);
        assertThat(context.originDecision()).as("Task가 어느 논의에서 나왔는지").isNotNull();
        assertThat(context.originEvidence()).as("근거 대화").isNotEmpty();

        TaskDeliverableSummary deliverable = taskResources.addDeliverable(workspaceId, minjun, perfTaskId,
                new DeliverableInput("성능 측정 결과", "pptx"));
        assertThat(taskService.getTask(perfTaskId).progress()).as("제출 전 진행률").isZero();

        ContentRefDTO uploaded = contents.upsert(new ContentRefUpsert(workspaceId, null, Provider.GOOGLE_DRIVE,
                ContentType.FILE, "drive-file-1", "folder-1", null, "발표자료_v1.pptx", "성능 측정 결과",
                "https://drive.google.com/file/d/drive-file-1", LocalDateTime.now()));
        when(drive.getFile(eq(workspaceId), anyLong(), eq(uploaded.contentRefId()))).thenReturn(uploaded);
        taskResources.submit(workspaceId, minjun, perfTaskId, deliverable.deliverableId(), uploaded.contentRefId());

        TaskSummary perfTask = taskService.getTask(perfTaskId);
        assertThat(perfTask.progress()).as("산출물을 내면 진행률이 오른다").isEqualTo(100);
        assertThat(perfTask.status()).isEqualTo(TaskStatus.DONE);
        assertThat(taskContextService.getTaskContext(perfTaskId).deliverables()).singleElement()
                .satisfies(d -> assertThat(d.submitted()).isTrue());

        // ===== 장면 6. 회의 추천 (UC-10-01 · 04~09, 07-01) =====
        LocalDateTime from = NEXT_TUESDAY.atTime(14, 0);
        MeetingDetailResponse meeting = meetingService.createMeeting(workspaceId, cheoljun, new MeetingCreateRequest(
                "질의응답 대비 문서 담당 정하기", 60, from, NEXT_TUESDAY.atTime(18, 0),
                List.of(cheoljun, minjun, chanseo), "온라인", List.of(demoTaskId)));
        assertThat(meeting.agendas()).as("안건으로 Task 선택").hasSize(1);

        // 민준은 캘린더에 16시 일정이 있다 → 자동 불가능
        when(calendar.isCalendarConnected(workspaceId, minjun)).thenReturn(true);
        when(calendar.getBusyTimes(eq(workspaceId), eq(minjun), any()))
                .thenReturn(List.of(new DateRange(NEXT_TUESDAY.atTime(16, 0), NEXT_TUESDAY.atTime(17, 0))));
        AvailabilityResponse mine = meetingService.getMyAvailability(workspaceId, meeting.meetingId(), minjun);
        assertThat(mine.busySlots()).as("캘린더 일정이 있는 시간은 자동으로 불가능").contains(NEXT_TUESDAY.atTime(16, 0));

        save(meeting, cheoljun, slots(from, 4, SlotPreference.AVAILABLE));            // 14:00~16:00 가능
        save(meeting, minjun, slots(from, 4, SlotPreference.AVAILABLE));              // 14:00~16:00 가능
        save(meeting, chanseo, List.of(
                new SlotInput(from, SlotPreference.NOT_PREFERRED),                     // 14시대는 비선호
                new SlotInput(from.plusMinutes(30), SlotPreference.NOT_PREFERRED),
                new SlotInput(from.plusMinutes(60), SlotPreference.AVAILABLE),         // 15시대는 가능
                new SlotInput(from.plusMinutes(90), SlotPreference.AVAILABLE)));
        var responses = meetingService.getResponseStatus(workspaceId, meeting.meetingId());
        assertThat(responses.responded()).as("응답 현황: 3명 모두 응답").hasSize(3);
        assertThat(responses.notResponded()).isEmpty();

        List<TimeCandidateResponse> candidates = meetingService.recommendTimes(workspaceId, meeting.meetingId(), cheoljun);
        TimeCandidateResponse best = candidates.getFirst();
        assertThat(best.startAt()).as("3명 모두 가능하고 비선호가 없는 15시가 1순위").isEqualTo(NEXT_TUESDAY.atTime(15, 0));
        assertThat(best.availableCount()).isEqualTo(3);
        assertThat(best.nonPreferredCount()).isZero();

        when(calendar.createMeetingEvent(eq(workspaceId), eq(cheoljun), anyString(), any(), any(), anyList(), any()))
                .thenAnswer(inv -> new CalendarEventResponse(contents.upsert(calendarUpsert("meeting-event")).contentRefId(),
                        inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), false, null, List.of(),
                        "https://calendar.google.com/e/1", "https://meet.google.com/abc", null, false));
        MeetingDetailResponse confirmed = meetingService.confirmMeeting(workspaceId, meeting.meetingId(), cheoljun, best.startAt());
        assertThat(confirmed.status()).isEqualTo(MeetingStatus.CONFIRMED);
        assertThat(confirmed.calendarRefId()).as("팀 캘린더에 등록").isNotNull();
        assertThat(confirmed.meetLink()).isNotBlank();

        // ===== 장면 7. 통합 검색 (UC-12-01, 11-02) =====
        when(vectorStore.searchVectors(any(), eq(workspaceId), anyList(), any(Integer.class))).thenReturn(List.of(
                new VectorHit(threadRoot.contentRefId(), 0.2), new VectorHit(notionPage.contentRefId(), 0.3)));
        taskResources.link(workspaceId, minjun, perfTaskId, uploaded.contentRefId());
        SearchResponse found = searchService.search(workspaceId, cheoljun, "발표 자료 마감 언제였지?", List.of(), 10);
        assertThat(found.items()).extracting(SearchResponse.Item::provider).as("Slack · Notion 결과")
                .contains(Provider.SLACK, Provider.NOTION);
        SearchResponse onlyNotion = searchService.search(workspaceId, cheoljun, "발표 자료 마감 언제였지?",
                List.of(Provider.NOTION), 10);
        assertThat(onlyNotion.items()).as("출처 필터").allSatisfy(i -> assertThat(i.provider()).isEqualTo(Provider.NOTION));
        SearchResponse driveResult = searchService.search(workspaceId, cheoljun, "발표자료_v1", List.of(), 10);
        assertThat(driveResult.items()).filteredOn(i -> i.contentRefId().equals(uploaded.contentRefId()))
                .as("결과에 연결된 Task가 함께 나온다").singleElement()
                .satisfies(i -> assertThat(i.tasks()).extracting("title").contains("성능 측정"));

        // ===== 장면 8. AI 업무 보조 (UC-13-01 · 02) =====
        LocalDateTime m0 = LocalDateTime.now(SlackApiClient.ZONE).minusHours(1).withNano(0);
        mail("mail-1", "최종발표 리허설 안내", "최종발표 리허설은 12월 3일(수) 14시에 진행합니다", m0);
        mail("mail-2", "발표자료 업로드", "발표자료는 12월 1일까지 인터넷디스크에 업로드해 주세요", m0.plusMinutes(1));
        mail("mail-3", "최종보고서", "최종보고서 제출 일정은 추후 공지합니다", m0.plusMinutes(2));
        int year = LocalDate.now(SlackApiClient.ZONE).getYear();
        when(gemini.generateJson(anyString(), eq(SuggestionService.AiResult.class))).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            return new SuggestionService.AiResult(List.of(
                    new SuggestionService.AiCandidate("SCHEDULE", "최종발표 리허설", label(prompt, "리허설은"),
                            year + "-12-03T14:00", null),
                    new SuggestionService.AiCandidate("TASK", "발표자료 인터넷디스크 업로드", label(prompt, "인터넷디스크"),
                            null, year + "-12-01"),
                    new SuggestionService.AiCandidate("SCHEDULE", "최종보고서 제출", label(prompt, "추후 공지"), null, null)));
        });
        DetectResultResponse detected = suggestionService.detectCandidates(workspaceId, cheoljun, null);
        verify(gmail).getContentsByUser(workspaceId, cheoljun);
        assertThat(detected.created()).as("메일에서 후보 3개").hasSize(3);

        SuggestionDTO undecided = byTitle(detected, "최종보고서 제출");
        assertThat(undecided.timeUndetermined()).as("'시간 미정' 표시").isTrue();
        suggestionService.updateSuggestion(workspaceId, cheoljun, undecided.suggestionId(),
                new SuggestionUpdateRequest("최종보고서 제출", LocalDateTime.of(year, 12, 10, 18, 0), null));

        when(calendar.createPersonalEvent(eq(workspaceId), eq(cheoljun), any())).thenReturn(calendarRef("personal-event"));
        suggestionService.approveSchedule(workspaceId, cheoljun, undecided.suggestionId());
        suggestionService.approveSchedule(workspaceId, cheoljun, byTitle(detected, "최종발표 리허설").suggestionId());
        Long personalTaskId = suggestionService.approveTask(workspaceId, cheoljun,
                byTitle(detected, "발표자료 인터넷디스크 업로드").suggestionId());
        assertThat(suggestionService.getSuggestions(workspaceId, cheoljun, null)).as("승인한 후보는 목록에서 빠진다")
                .noneMatch(s -> s.status() == SuggestionStatus.PENDING);

        suggestionService.completePersonalTask(cheoljun, personalTaskId);
        MyTodoListDTO todo = suggestionService.getMyTodoList(workspaceId, cheoljun);
        assertThat(todo.personalTasks()).filteredOn(t -> t.personalTaskId().equals(personalTaskId))
                .as("내 할 일 목록에서 완료 처리").singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(PersonalTaskStatus.DONE));
        assertThat(todo.assignedTasks()).as("철준이 맡은 '시연 준비'도 내 할 일에 보인다")
                .extracting(MyTodoListDTO.AssignedTask::title).contains("시연 준비");
    }

    @Test
    @DisplayName("장애 대응: 논의 정리가 실패하면 FAILED로 남고, 외부에 반영하지 않으며, 다시 분석할 수 있다")
    void 논의_정리_실패_후_재시도() {
        when(gemini.generateJson(anyString(), eq(DiscussionAnalyzer.AiResult.class)))
                .thenThrow(new RuntimeException("Gemini 503"))
                .thenAnswer(inv -> discussionResult(inv.getArgument(0)));

        assertThatThrownBy(() -> analysisService.analyzeThread(workspaceId, threadRoot.contentRefId(), cheoljun))
                .as("화면에는 'AI 분석 호출 실패'로 알린다")
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_CALL_FAILED));
        Long failedId = analysisService.findLatestAnalysisId(workspaceId, threadRoot.contentRefId()).orElseThrow();
        assertThat(analysisService.getAnalysis(workspaceId, failedId).status()).as("실패 상태를 저장한다")
                .isEqualTo(AnalysisStatus.FAILED);
        verifyNoInteractions(notion, calendar);

        AnalysisResultDTO retried = analysisService.analyzeThread(workspaceId, threadRoot.contentRefId(), cheoljun);
        assertThat(retried.status()).as("재시도하면 정상 결과").isEqualTo(AnalysisStatus.REVIEWING);
        assertThat(retried.items()).isNotEmpty();
    }

    // ===================== 가짜 응답 =====================

    /** 시연 스레드에 대한 Gemini 논의 분석 결과. 근거 번호는 실제 프롬프트에서 찾는다. */
    private DiscussionAnalyzer.AiResult discussionResult(String prompt) {
        String drive = label(prompt, "드라이브에 올리자");
        String roles = label(prompt, "성능 측정은");
        String k6 = label(prompt, "k6로 하자");
        String fifty = label(prompt, "50명 기준");
        String rehearsal = label(prompt, "리허설 하자");
        String open = label(prompt, "아직 못 정함");
        return new DiscussionAnalyzer.AiResult(List.of(
                new DiscussionAnalyzer.AiItem("DECISION", "최종발표 PPT는 금요일까지 드라이브에 업로드", List.of(drive),
                        null, null, null, null),
                new DiscussionAnalyzer.AiItem("DECISION", "부하 테스트는 k6로, 50명 기준", List.of(k6, fifty),
                        null, null, null, null),
                new DiscussionAnalyzer.AiItem("FOLLOW_UP", "시연 준비", List.of(roles), "U1", null, null, null),
                new DiscussionAnalyzer.AiItem("FOLLOW_UP", "성능 측정", List.of(roles), "U2", null, null, null),
                new DiscussionAnalyzer.AiItem("OPEN_ISSUE", "질의응답 대비 문서 담당자 미정", List.of(open),
                        null, null, null, null),
                new DiscussionAnalyzer.AiItem("MEETING_PROPOSAL", "최종발표 리허설", List.of(rehearsal), null, null,
                        NEXT_TUESDAY.atTime(15, 0).toString(), List.of())), true);
    }

    /** 프롬프트에서 해당 문구가 든 줄의 번호(m1, s2 …)를 찾는다. */
    private static String label(String prompt, String phrase) {
        for (String line : prompt.split("\n")) {
            if (line.contains(phrase)) {
                Matcher m = Pattern.compile("^\\s*([a-z]\\d+)").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        throw new AssertionError("프롬프트에 '" + phrase + "' 메시지가 없다");
    }

    // ===================== 데이터 준비 =====================

    private ContentRefDTO slack(String ts, Long parent, String author, String name, String text, LocalDateTime at) {
        return contents.upsert(new ContentRefUpsert(workspaceId, null, Provider.SLACK, ContentType.MESSAGE,
                "C1:" + ts + ":" + workspaceId, "C1", parent, "#프로젝트", text, null, at, at, author, name));
    }

    private void mail(String id, String subject, String body, LocalDateTime at) {
        contents.upsert(new ContentRefUpsert(workspaceId, cheoljun, Provider.GMAIL, ContentType.MAIL, id + ":" + workspaceId,
                null, null, subject, body, "https://mail.google.com/" + id, at, at, "demo@test", "시연 계정"));
    }

    private ContentRefUpsert calendarUpsert(String id) {
        return new ContentRefUpsert(workspaceId, null, Provider.GOOGLE_CALENDAR, ContentType.EVENT, id + ":" + System.nanoTime(),
                "team-calendar", null, id, null, "https://calendar.google.com/" + id, LocalDateTime.now());
    }

    private ContentRefDTO calendarRef(String id) {
        return contents.upsert(calendarUpsert(id));
    }

    private void save(MeetingDetailResponse meeting, Long userId, List<SlotInput> slots) {
        meetingService.saveAvailability(workspaceId, meeting.meetingId(), userId, new AvailabilitySaveRequest(slots));
    }

    private static List<SlotInput> slots(LocalDateTime from, int count, SlotPreference preference) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new SlotInput(from.plusMinutes(30L * i), preference)).toList();
    }

    private static int count(AnalysisResultDTO analysis, ItemType type) {
        return (int) analysis.items().stream().filter(i -> i.itemType() == type).count();
    }

    private static AnalysisItemResponse followUp(AnalysisResultDTO analysis, String content) {
        return analysis.items().stream()
                .filter(i -> i.itemType() == ItemType.FOLLOW_UP && i.content().contains(content))
                .findFirst().orElseThrow();
    }

    private static SuggestionDTO byTitle(DetectResultResponse detected, String title) {
        return detected.created().stream().filter(s -> s.title().equals(title)).findFirst().orElseThrow();
    }
}
