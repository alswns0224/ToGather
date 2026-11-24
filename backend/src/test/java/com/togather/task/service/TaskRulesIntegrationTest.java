package com.togather.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.togather.common.auth.LoginUserArgumentResolver;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.MeetingStatus;
import com.togather.common.enums.Provider;
import com.togather.common.enums.TaskStatus;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.meeting.dto.MeetingCreateRequest;
import com.togather.meeting.dto.MeetingDetailResponse;
import com.togather.meeting.service.MeetingService;
import com.togather.support.IntegrationTestSupport;
import com.togather.task.dto.DeliverableInput;
import com.togather.task.dto.TaskCommand;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class TaskRulesIntegrationTest extends IntegrationTestSupport {

    private static final String FOLDER = "task-rules-folder";

    @Autowired private TaskService taskService;
    @Autowired private TaskResourceService resources;
    @Autowired private MeetingService meetingService;
    @Autowired private ContentRefService contents;
    @Autowired private WebApplicationContext webContext;
    @Autowired private DataSource dataSource;
    @MockitoBean private IntegrationService integrationService;

    private Long userId;
    private Long workspaceId;
    private MockMvc mvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        userId = createUser("Task 규칙 검증", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(userId);
        mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        session = new MockHttpSession(webContext.getServletContext());
        session.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, userId);
        when(integrationService.getConnectionTargets(workspaceId, Provider.GOOGLE_DRIVE))
            .thenReturn(List.of(FOLDER));
    }

    @Test
    void 산출물이_있는_Task를_완료_상태로_요청해도_생성시_미제출_상태를_반영한다() throws Exception {
        mvc.perform(post(base()).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"생성 규칙","status":"DONE","deliverables":[{"name":"발표 자료","ext":"pdf"}]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.progress").value(0));
    }

    @Test
    void 미제출_Task의_수동_완료를_거절하고_다른_수정값도_저장하지_않는다() throws Exception {
        Long taskId = createTask(true);
        mvc.perform(patch(path(taskId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"저장되면 안 됨\",\"status\":\"DONE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("TASK_STATUS_MANAGED_BY_DELIVERABLES"));
        assertThat(taskService.getTask(taskId).title()).isEqualTo("검증 Task");
        assertThat(taskService.getTask(taskId).status()).isEqualTo(TaskStatus.IN_PROGRESS);
        mvc.perform(get(path(taskId)).session(session))
            .andExpect(jsonPath("$.data.statusManagedByDeliverables").value(true));
    }

    @Test
    void 제출_완료_Task를_수동으로_되돌릴_수_없고_제출_해제로만_되돌아간다() throws Exception {
        Long taskId = createTask(true);
        Long deliverableId = jdbc.queryForObject("SELECT deliverable_id FROM task_deliverable WHERE task_id = ?",
            Long.class, taskId);
        Long fileId = driveFile();
        resources.submit(workspaceId, userId, taskId, deliverableId, fileId);
        assertThat(taskService.getTask(taskId).status()).isEqualTo(TaskStatus.DONE);

        mvc.perform(patch(path(taskId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isConflict());
        // 기존 클라이언트가 현재와 같은 상태를 함께 보내도 일반 정보 수정은 허용한다.
        mvc.perform(patch(path(taskId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제출 완료 작업\",\"status\":\"DONE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DONE"));
        resources.unsubmit(workspaceId, userId, taskId, deliverableId);
        assertThat(taskService.getTask(taskId).status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(taskService.getTask(taskId).progress()).isZero();
    }

    @Test
    void 산출물이_없는_Task의_수동_상태_변경은_유지한다() throws Exception {
        Long taskId = createTask(false);
        mvc.perform(get(path(taskId)).session(session))
            .andExpect(jsonPath("$.data.statusManagedByDeliverables").value(false));
        mvc.perform(patch(path(taskId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.progress").value(100));
        mvc.perform(patch(path(taskId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.progress").value(0));
    }

    @ParameterizedTest
    @EnumSource(value = MeetingStatus.class, names = {"SCHEDULING", "CONFIRMED"})
    void 조율중_확정_회의의_안건_Task는_API로_직접_삭제해도_보호한다(MeetingStatus meetingStatus) throws Exception {
        Long taskId = createTask(false);
        MeetingDetailResponse meeting = createMeeting(taskId);
        jdbc.update("UPDATE meeting SET status = ? WHERE meeting_id = ?", meetingStatus.name(), meeting.meetingId());

        mvc.perform(get(path(taskId) + "/deletion-blockers").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].meetingId").value(meeting.meetingId()))
            .andExpect(jsonPath("$.data[0].title").value("삭제 보호 회의"));
        mvc.perform(delete(path(taskId)).session(session))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("TASK_IN_ACTIVE_MEETING"));
        assertThat(taskService.getTask(taskId).taskId()).isEqualTo(taskId);
        assertThat(meetingService.getMeeting(workspaceId, meeting.meetingId(), userId).agendas())
            .hasSize(1);
    }

    @Test
    void 취소된_회의만_남으면_Task를_삭제하고_원본_자료는_유지한다() throws Exception {
        Long taskId = createTask(true);
        MeetingDetailResponse meeting = createMeeting(taskId);
        meetingService.cancelMeeting(workspaceId, meeting.meetingId(), userId);
        Long fileId = driveFile();
        resources.link(workspaceId, userId, taskId, fileId);

        mvc.perform(get(path(taskId) + "/deletion-blockers").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(delete(path(taskId)).session(session)).andExpect(status().isNoContent());
        mvc.perform(get(path(taskId)).session(session)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_deliverable WHERE task_id = ?", Long.class, taskId))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_content_link WHERE task_id = ?", Long.class, taskId))
            .isZero();
        assertThat(meetingService.getMeeting(workspaceId, meeting.meetingId(), userId).agendas()).isEmpty();
        assertThat(contents.getContentRef(fileId).contentRefId()).isEqualTo(fileId);
    }

    @Test
    void 진행중_회의의_안건을_해제한_뒤에는_Task를_삭제할_수_있다() throws Exception {
        Long taskId = createTask(false);
        MeetingDetailResponse meeting = createMeeting(taskId);
        meetingService.deleteAgenda(workspaceId, meeting.meetingId(), meeting.agendas().getFirst().agendaId(), userId);
        mvc.perform(get(path(taskId) + "/deletion-blockers").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(delete(path(taskId)).session(session)).andExpect(status().isNoContent());
        assertThat(meetingService.getMeeting(workspaceId, meeting.meetingId(), userId).title())
            .isEqualTo("삭제 보호 회의");
    }

    @Test
    void 삭제와_차단_회의_조회는_인증_멤버십_Task_소속을_검사한다() throws Exception {
        Long taskId = createTask(false);
        createMeeting(taskId);
        Long outsider = createUser("외부 사용자", UUID.randomUUID() + "@test.example");
        var outsiderSession = new MockHttpSession(webContext.getServletContext());
        outsiderSession.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, outsider);
        Long otherWorkspace = createWorkspace(userId);
        String foreignPath = "/api/workspaces/" + otherWorkspace + "/tasks/" + taskId;

        mvc.perform(get(path(taskId) + "/deletion-blockers")).andExpect(status().isUnauthorized());
        mvc.perform(delete(path(taskId))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(taskId) + "/deletion-blockers").session(outsiderSession))
            .andExpect(status().isForbidden());
        mvc.perform(delete(path(taskId)).session(outsiderSession)).andExpect(status().isForbidden());
        mvc.perform(get(foreignPath + "/deletion-blockers").session(session)).andExpect(status().isNotFound());
        mvc.perform(delete(foreignPath).session(session)).andExpect(status().isNotFound());
    }

    @Test
    void 이전_수동_상태를_복구하는_마이그레이션은_산출물_없는_Task를_변경하지_않는다() {
        Long incomplete = createTask(true);
        Long complete = createTask(true);
        Long manualDone = createTask(false);
        Long manualPending = createTask(false);
        jdbc.update("UPDATE task SET status = 'DONE' WHERE task_id IN (?, ?)", incomplete, manualDone);
        jdbc.update("UPDATE task_deliverable SET is_submitted = true WHERE task_id = ?", complete);

        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/V10__align_task_status_with_deliverables.sql")).execute(dataSource);

        assertThat(taskService.getTask(incomplete).status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(taskService.getTask(complete).status()).isEqualTo(TaskStatus.DONE);
        assertThat(taskService.getTask(manualDone).status()).isEqualTo(TaskStatus.DONE);
        assertThat(taskService.getTask(manualPending).status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    private Long createTask(boolean withDeliverable) {
        return taskService.createTask(workspaceId, new TaskCommand("검증 Task", null, userId, null,
            TaskStatus.IN_PROGRESS,
            withDeliverable ? List.of(new DeliverableInput("결과물", "pdf")) : List.of(), null));
    }

    private MeetingDetailResponse createMeeting(Long taskId) {
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        return meetingService.createMeeting(workspaceId, userId, new MeetingCreateRequest("삭제 보호 회의", 60,
            start, start.plusDays(1), List.of(userId), "온라인", List.of(taskId)));
    }

    private Long driveFile() {
        return contents.upsert(new ContentRefUpsert(workspaceId, null, Provider.GOOGLE_DRIVE, ContentType.FILE,
            UUID.randomUUID().toString(), FOLDER, null, "결과.pdf", "파일 정보", "https://example.com/file",
            LocalDateTime.now())).contentRefId();
    }

    private String base() {
        return "/api/workspaces/" + workspaceId + "/tasks";
    }

    private String path(Long taskId) {
        return base() + "/" + taskId;
    }
}
