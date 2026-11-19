package com.togather.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.togather.common.auth.LoginUserArgumentResolver;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.enums.TaskStatus;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.support.IntegrationTestSupport;
import com.togather.task.dto.TaskCommand;
import com.togather.task.dto.TaskSummary;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class DeliverableManagementTest extends IntegrationTestSupport {

    private static final String FOLDER = "deliverable-edit-folder";

    @Autowired private TaskService taskService;
    @Autowired private TaskResourceService resources;
    @Autowired private TaskContextService contexts;
    @Autowired private ContentRefService contents;
    @Autowired private WebApplicationContext webContext;
    @MockitoBean private IntegrationService integrationService;

    private Long userId;
    private Long workspaceId;
    private MockMvc mvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        userId = createUser("산출물 항목 검증", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(userId);
        mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        session = sessionFor(userId);
        when(integrationService.getConnectionTargets(workspaceId, Provider.GOOGLE_DRIVE))
            .thenReturn(List.of(FOLDER));
    }

    @Test
    void 연결된_Drive_없이_완료_Task에_항목을_추가하면_진행중으로_돌아간다() throws Exception {
        Long taskId = createTask(TaskStatus.DONE);
        when(integrationService.getConnectionTargets(workspaceId, Provider.GOOGLE_DRIVE)).thenReturn(List.of());

        Long deliverableId = add(taskId, "  최종 자료  ", "");

        assertThat(deliverableId).isPositive();
        assertThat(jdbc.queryForObject("SELECT name FROM task_deliverable WHERE deliverable_id = ?",
            String.class, deliverableId)).isEqualTo("최종 자료");
        assertState(taskId, TaskStatus.IN_PROGRESS, 0, 1);
        mvc.perform(get(path(taskId) + "/context").session(session))
            .andExpect(jsonPath("$.data.task.statusManagedByDeliverables").value(true))
            .andExpect(jsonPath("$.data.deliverables[0].deliverableId").value(deliverableId))
            .andExpect(jsonPath("$.data.deliverables[0].submitted").value(false));
    }

    @Test
    void 제출_완료_Task에_항목을_추가하면_기존_제출을_유지하고_진행률을_다시_계산한다() throws Exception {
        Long taskId = createTask(TaskStatus.IN_PROGRESS);
        Long original = add(taskId, "발표 자료", ".PDF");
        resources.submit(workspaceId, userId, taskId, original, driveFile());
        assertState(taskId, TaskStatus.DONE, 100, 1);

        Long added = add(taskId, "추가 자료", "pdf");
        assertState(taskId, TaskStatus.IN_PROGRESS, 50, 2);
        assertThat(contexts.getTaskContext(taskId).deliverables())
            .filteredOn(deliverable -> deliverable.deliverableId().equals(original))
            .allMatch(deliverable -> deliverable.submitted() && deliverable.fileName().equals("결과.pdf"));
        resources.submit(workspaceId, userId, taskId, added, driveFile());
        assertState(taskId, TaskStatus.DONE, 100, 2);
    }

    @Test
    void 미제출_항목을_삭제해_남은_항목이_모두_제출됐으면_완료가_된다() throws Exception {
        Long taskId = createTask(TaskStatus.IN_PROGRESS);
        Long submitted = add(taskId, "제출 자료", "pdf");
        Long pending = add(taskId, "불필요한 자료", "pdf");
        resources.submit(workspaceId, userId, taskId, submitted, driveFile());
        assertState(taskId, TaskStatus.IN_PROGRESS, 50, 2);

        remove(taskId, pending);
        assertState(taskId, TaskStatus.DONE, 100, 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_deliverable WHERE deliverable_id = ?",
            Long.class, pending)).isZero();
    }

    @Test
    void 제출된_항목을_삭제하면_남은_미제출_항목만으로_진행률을_계산한다() throws Exception {
        Long taskId = createTask(TaskStatus.IN_PROGRESS);
        Long submitted = add(taskId, "완료 자료", "pdf");
        add(taskId, "남은 자료", "pdf");
        resources.submit(workspaceId, userId, taskId, submitted, driveFile());

        remove(taskId, submitted);
        assertState(taskId, TaskStatus.IN_PROGRESS, 0, 1);
    }

    @Test
    void 마지막_미제출_항목_삭제는_진행중을_유지하고_수동_완료를_허용한다() throws Exception {
        Long taskId = createTask(TaskStatus.IN_PROGRESS);
        Long deliverableId = add(taskId, "자료", "");
        remove(taskId, deliverableId);
        assertState(taskId, TaskStatus.IN_PROGRESS, 0, 0);
        mvc.perform(get(path(taskId)).session(session))
            .andExpect(jsonPath("$.data.statusManagedByDeliverables").value(false));
        mvc.perform(patch(path(taskId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.progress").value(100));
    }

    @Test
    void 마지막_제출_항목_삭제는_완료를_유지하고_수동_진행중_변경을_허용한다() throws Exception {
        Long taskId = createTask(TaskStatus.IN_PROGRESS);
        Long deliverableId = add(taskId, "자료", "pdf");
        resources.submit(workspaceId, userId, taskId, deliverableId, driveFile());

        remove(taskId, deliverableId);
        assertState(taskId, TaskStatus.DONE, 100, 0);
        mvc.perform(get(path(taskId)).session(session))
            .andExpect(jsonPath("$.data.statusManagedByDeliverables").value(false));
        mvc.perform(patch(path(taskId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.progress").value(0));
    }

    @Test
    void 항목_삭제는_원본_파일과_관련자료_연결과_다른_Task의_제출을_유지한다() throws Exception {
        Long taskId = createTask(TaskStatus.IN_PROGRESS);
        Long otherTask = createTask(TaskStatus.IN_PROGRESS);
        Long first = add(taskId, "첫 제출", "pdf");
        Long second = add(otherTask, "다른 제출", "pdf");
        Long file = driveFile();
        resources.submit(workspaceId, userId, taskId, first, file);
        resources.submit(workspaceId, userId, otherTask, second, file);
        resources.link(workspaceId, userId, taskId, file);

        remove(taskId, first);
        assertThat(contents.getContentRef(file).title()).isEqualTo("결과.pdf");
        assertThat(contents.getOwner(file).isOriginDeleted()).isFalse();
        assertThat(contexts.getTaskContext(taskId).contentsByProvider().get(Provider.GOOGLE_DRIVE))
            .extracting(content -> content.contentRefId()).containsExactly(file);
        assertThat(contexts.getTaskContext(otherTask).deliverables()).hasSize(1)
            .allMatch(deliverable -> deliverable.submitted() && deliverable.deliverableId().equals(second));
        assertThat(contexts.getTasksByContents(List.of(file)).get(file)).extracting(TaskSummary::taskId)
            .containsExactlyInAnyOrder(taskId, otherTask);

        resources.unlink(workspaceId, userId, taskId, file);
        assertThat(contexts.getTasksByContents(List.of(file)).get(file)).extracting(TaskSummary::taskId)
            .containsExactly(otherTask);
    }

    @Test
    void 일반_팀원도_기존_Task의_항목을_추가하고_삭제할_수_있다() throws Exception {
        Long taskId = createTask(TaskStatus.IN_PROGRESS);
        Long member = createUser("일반 팀원", UUID.randomUUID() + "@test.example");
        addMember(workspaceId, member, "MEMBER");
        MockHttpSession memberSession = sessionFor(member);
        String response = mvc.perform(post(path(taskId) + "/deliverables").session(memberSession)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"새 항목\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.ext").isEmpty())
            .andReturn().getResponse().getContentAsString();
        Long deliverableId = ((Number) JsonPath.read(response, "$.data.deliverableId")).longValue();
        mvc.perform(delete(path(taskId) + "/deliverables/" + deliverableId).session(memberSession))
            .andExpect(status().isNoContent());
        assertState(taskId, TaskStatus.IN_PROGRESS, 0, 0);
    }

    @Test
    void 추가와_삭제는_인증_멤버십_Task와_항목의_소속을_검사한다() throws Exception {
        Long taskId = createTask(TaskStatus.IN_PROGRESS);
        Long deliverableId = add(taskId, "보호 항목", "pdf");
        Long outsider = createUser("외부 사용자", UUID.randomUUID() + "@test.example");
        MockHttpSession outsiderSession = sessionFor(outsider);
        Long foreignWorkspace = createWorkspace(userId);
        String foreignPath = "/api/workspaces/" + foreignWorkspace + "/tasks/" + taskId + "/deliverables";
        String entries = path(taskId) + "/deliverables";
        String body = "{\"name\":\"변경 금지\",\"ext\":\"pdf\"}";

        mvc.perform(post(entries).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete(entries + "/" + deliverableId)).andExpect(status().isUnauthorized());
        mvc.perform(post(entries).session(outsiderSession).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(delete(entries + "/" + deliverableId).session(outsiderSession))
            .andExpect(status().isForbidden());
        mvc.perform(post(foreignPath).session(session).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
        mvc.perform(delete(foreignPath + "/" + deliverableId).session(session))
            .andExpect(status().isNotFound());
        Long otherTask = createTask(TaskStatus.IN_PROGRESS);
        mvc.perform(delete(path(otherTask) + "/deliverables/" + deliverableId).session(session))
            .andExpect(status().isNotFound());
        mvc.perform(delete(entries + "/9223372036854775807").session(session))
            .andExpect(status().isNotFound());
        assertState(taskId, TaskStatus.IN_PROGRESS, 0, 1);
        assertState(otherTask, TaskStatus.IN_PROGRESS, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"name\":\" \"}", "{\"name\":\"자료\",\"ext\":\".\"}",
        "{\"name\":\"자료\",\"ext\":\"pdf/zip\"}", "{\"name\":\"자료\",\"ext\":\"pdf exe\"}", "null"
    })
    void 잘못된_입력은_추가하지_않고_완료_상태도_유지한다(String body) throws Exception {
        Long taskId = createTask(TaskStatus.DONE);
        mvc.perform(post(path(taskId) + "/deliverables").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest());
        assertState(taskId, TaskStatus.DONE, 100, 0);
    }

    @Test
    void 이름과_확장자의_길이_제한을_검사한다() throws Exception {
        Long taskId = createTask(TaskStatus.DONE);
        mvc.perform(post(path(taskId) + "/deliverables").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "a".repeat(201) + "\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path(taskId) + "/deliverables").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"자료\",\"ext\":\"" + "a".repeat(21) + "\"}"))
            .andExpect(status().isBadRequest());
        assertState(taskId, TaskStatus.DONE, 100, 0);
        add(taskId, "a".repeat(200), "a".repeat(20));
        assertState(taskId, TaskStatus.IN_PROGRESS, 0, 1);
    }

    private Long createTask(TaskStatus status) {
        return taskService.createTask(workspaceId, new TaskCommand("항목 관리 Task", null, userId, null,
            status, List.of(), null));
    }

    private Long add(Long taskId, String name, String ext) throws Exception {
        String response = mvc.perform(post(path(taskId) + "/deliverables").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"ext\":\"" + ext + "\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.submitted").value(false))
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.deliverableId")).longValue();
    }

    private void remove(Long taskId, Long deliverableId) throws Exception {
        mvc.perform(delete(path(taskId) + "/deliverables/" + deliverableId).session(session))
            .andExpect(status().isNoContent());
    }

    private void assertState(Long taskId, TaskStatus status, int progress, int count) {
        assertThat(taskService.getTask(taskId).status()).isEqualTo(status);
        assertThat(taskService.getTask(taskId).progress()).isEqualTo(progress);
        assertThat(contexts.getTaskContext(taskId).deliverables()).hasSize(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_deliverable WHERE task_id = ?", Long.class, taskId))
            .isEqualTo((long) count);
    }

    private Long driveFile() {
        return contents.upsert(new ContentRefUpsert(workspaceId, null, Provider.GOOGLE_DRIVE, ContentType.FILE,
            UUID.randomUUID().toString(), FOLDER, null, "결과.pdf", "파일 정보", "https://example.com/file",
            LocalDateTime.now())).contentRefId();
    }

    private MockHttpSession sessionFor(Long id) {
        var result = new MockHttpSession(webContext.getServletContext());
        result.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, id);
        return result;
    }

    private String path(Long taskId) {
        return "/api/workspaces/" + workspaceId + "/tasks/" + taskId;
    }
}
