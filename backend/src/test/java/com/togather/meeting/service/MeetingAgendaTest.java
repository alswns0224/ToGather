package com.togather.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.togather.common.auth.LoginUserArgumentResolver;
import com.togather.common.enums.TaskStatus;
import com.togather.meeting.dto.MeetingCreateRequest;
import com.togather.meeting.dto.MeetingDetailResponse;
import com.togather.support.IntegrationTestSupport;
import com.togather.task.dto.TaskCommand;
import com.togather.task.service.TaskService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class MeetingAgendaTest extends IntegrationTestSupport {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 26, 9, 0);
    @Autowired private MeetingService meetings;
    @Autowired private TaskService tasks;
    @Autowired private WebApplicationContext context;
    private Long userId;
    private Long memberId;
    private Long workspaceId;
    private MockMvc mvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        userId = createUser("안건 작성자", UUID.randomUUID() + "@test.example");
        memberId = createUser("일반 참여자", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(userId);
        addMember(workspaceId, memberId, "MEMBER");
        session = session(userId);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 직접_입력과_Task_안건을_함께_저장하고_다시_조회한다() {
        Long taskId = task(workspaceId, "연결할 작업");
        MeetingDetailResponse result = meetings.createMeeting(workspaceId, userId, new MeetingCreateRequest(
                "주간 회의", 60, START, START.plusDays(2), List.of(memberId), "온라인",
                List.of(taskId, taskId), List.of("  발표 순서 논의  ", "다음 일정 결정")));
        MeetingDetailResponse loaded = meetings.getMeeting(workspaceId, result.meetingId(), userId);
        assertThat(loaded.agendas()).hasSize(3);
        assertThat(loaded.agendas()).extracting(a -> a.orderNo()).containsExactly(1, 2, 3);
        assertThat(loaded.agendas()).extracting(a -> a.title()).containsExactly("발표 순서 논의", "다음 일정 결정", null);
        assertThat(loaded.agendas().getFirst().taskId()).isNull();
        assertThat(loaded.agendas().getFirst().task()).isNull();
        assertThat(loaded.agendas().getLast().taskId()).isEqualTo(taskId);
        assertThat(loaded.agendas().getLast().task().title()).isEqualTo("연결할 작업");
        assertThat(taskCount()).isEqualTo(1);
    }

    @Test
    void 생성_API에_직접_입력만_보내면_Task를_만들지_않는다() throws Exception {
        mvc.perform(post(base()).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("[\"역할 분담\",\"시연 순서\"]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.agendas.length()").value(2))
                .andExpect(jsonPath("$.data.agendas[0].title").value("역할 분담"))
                .andExpect(jsonPath("$.data.agendas[0].taskId").isEmpty())
                .andExpect(jsonPath("$.data.agendas[1].title").value("시연 순서"));
        assertThat(taskCount()).isZero();
    }

    @Test
    void 기존_생성_호출과_Task_추가_요청도_그대로_동작한다() throws Exception {
        Long taskId = task(workspaceId, "기존 Task 안건");
        MeetingDetailResponse meeting = emptyMeeting();
        mvc.perform(post(path(meeting) + "/agendas").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + taskId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agendas[0].taskId").value(taskId))
                .andExpect(jsonPath("$.data.agendas[0].task.title").value("기존 Task 안건"))
                .andExpect(jsonPath("$.data.agendas[0].title").isEmpty());
        mvc.perform(post(path(meeting) + "/agendas").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + taskId + "}"))
                .andExpect(jsonPath("$.data.agendas.length()").value(1));
    }

    @Test
    void 상세에서_직접_안건을_추가_수정_전환_삭제한다() throws Exception {
        MeetingDetailResponse meeting = emptyMeeting();
        mvc.perform(post(path(meeting) + "/agendas").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  발표 순서 논의  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agendas[0].title").value("발표 순서 논의"));
        Long agendaId = meetings.getMeeting(workspaceId, meeting.meetingId(), userId).agendas().getFirst().agendaId();
        String agendaPath = path(meeting) + "/agendas/" + agendaId;
        mvc.perform(patch(agendaPath).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"새 논의 주제\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agendas[0].title").value("새 논의 주제"));
        Long taskId = task(workspaceId, "전환할 작업");
        mvc.perform(patch(agendaPath).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"taskId\":" + taskId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agendas[0].taskId").value(taskId))
                .andExpect(jsonPath("$.data.agendas[0].title").isEmpty());
        mvc.perform(patch(agendaPath).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"다시 직접 입력\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agendas[0].taskId").isEmpty())
                .andExpect(jsonPath("$.data.agendas[0].title").value("다시 직접 입력"));
        mvc.perform(delete(agendaPath).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agendas").isEmpty());
        assertThat(taskCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":\"\"}", "{\"title\":\"   \"}", "{\"taskId\":123,\"title\":\"동시에 지정\"}"})
    void 안건_종류가_없거나_둘이면_거절한다(String body) throws Exception {
        MeetingDetailResponse meeting = emptyMeeting();
        mvc.perform(post(path(meeting) + "/agendas").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        assertThat(meetings.getMeeting(workspaceId, meeting.meetingId(), userId).agendas()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"\"]", "[\"   \"]", "[null]"})
    void 생성할_직접_안건의_빈_값을_거절한다(String titles) throws Exception {
        mvc.perform(post(base()).session(session).contentType(MediaType.APPLICATION_JSON).content(createBody(titles)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting WHERE workspace_id = ?", Long.class, workspaceId)).isZero();
    }

    @Test
    void 제목_길이와_직접_안건_개수를_검증한다() throws Exception {
        String longTitle = "가".repeat(201);
        mvc.perform(post(base()).session(session).contentType(MediaType.APPLICATION_JSON).content(createBody("[\"" + longTitle + "\"]")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(base()).session(session).contentType(MediaType.APPLICATION_JSON).content(createBody("[" + String.join(",", java.util.Collections.nCopies(51, "\"안건\"")) + "]")))
                .andExpect(status().isBadRequest());
        MeetingDetailResponse meeting = emptyMeeting();
        mvc.perform(post(path(meeting) + "/agendas").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + longTitle + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 일반_참여자와_외부인은_안건을_변경할_수_없다() throws Exception {
        MeetingDetailResponse meeting = emptyMeeting();
        String target = path(meeting) + "/agendas";
        mvc.perform(post(target).session(session(memberId)).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"변경 시도\"}"))
                .andExpect(status().isForbidden());
        Long outsider = createUser("외부인", UUID.randomUUID() + "@test.example");
        mvc.perform(post(target).session(session(outsider)).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"외부 시도\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(target).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"미로그인\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_회의의_안건이나_다른_워크스페이스의_Task를_바꿀_수_없다() throws Exception {
        MeetingDetailResponse first = meetings.addAgenda(workspaceId, emptyMeeting().meetingId(), userId, null, "첫 회의 안건");
        MeetingDetailResponse second = emptyMeeting();
        Long agendaId = first.agendas().getFirst().agendaId();
        mvc.perform(patch(path(second) + "/agendas/" + agendaId).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"가로채기\"}"))
                .andExpect(status().isNotFound());
        Long otherWorkspace = createWorkspace(userId);
        Long otherTask = task(otherWorkspace, "다른 워크스페이스 작업");
        mvc.perform(post(path(second) + "/agendas").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"taskId\":" + otherTask + "}"))
                .andExpect(status().isNotFound());
        assertThat(meetings.getMeeting(workspaceId, first.meetingId(), userId).agendas().getFirst().title()).isEqualTo("첫 회의 안건");
    }

    @Test
    void 취소된_회의의_안건은_추가_수정_삭제할_수_없다() throws Exception {
        MeetingDetailResponse meeting = meetings.addAgenda(workspaceId, emptyMeeting().meetingId(), userId, null, "유지할 안건");
        Long agendaId = meeting.agendas().getFirst().agendaId();
        meetings.cancelMeeting(workspaceId, meeting.meetingId(), userId);
        mvc.perform(post(path(meeting) + "/agendas").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"추가\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(path(meeting) + "/agendas/" + agendaId).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"수정\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(path(meeting) + "/agendas/" + agendaId).session(session)).andExpect(status().isBadRequest());
        mvc.perform(get(path(meeting)).session(session)).andExpect(jsonPath("$.data.agendas[0].title").value("유지할 안건"));
    }

    private MeetingDetailResponse emptyMeeting() {
        return meetings.createMeeting(workspaceId, userId, new MeetingCreateRequest("안건 검증 회의", 60, START, START.plusDays(2), List.of(memberId), "온라인", List.of()));
    }
    private Long task(Long workspace, String title) {
        return tasks.createTask(workspace, new TaskCommand(title, null, userId, null, TaskStatus.IN_PROGRESS, List.of(), null));
    }
    private Long taskCount() { return jdbc.queryForObject("SELECT count(*) FROM task WHERE workspace_id = ?", Long.class, workspaceId); }
    private String base() { return "/api/workspaces/" + workspaceId + "/meetings"; }
    private String path(MeetingDetailResponse meeting) { return base() + "/" + meeting.meetingId(); }
    private static MockHttpSession session(Long userId) { MockHttpSession session = new MockHttpSession(); session.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, userId); return session; }
    private static String createBody(String titles) {
        return "{\"title\":\"직접 안건 회의\",\"durationMinutes\":60,\"candidateStartAt\":\"2026-11-26T09:00:00\",\"candidateEndAt\":\"2026-11-28T18:00:00\",\"agendaTitles\":" + titles + "}";
    }
}
