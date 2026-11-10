package com.togather.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.togather.common.auth.LoginUserArgumentResolver;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.enums.TaskStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.support.IntegrationTestSupport;
import com.togather.task.dto.DeliverableInput;
import com.togather.task.dto.TaskCommand;
import com.togather.task.dto.TaskReferenceCandidate;
import com.togather.task.dto.TaskSummary;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class TaskResourceServiceTest extends IntegrationTestSupport {

    private static final String FOLDER = "c04-test-folder";

    @Autowired
    private TaskResourceService resources;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskContextService taskContextService;
    @Autowired
    private ContentRefService contentRefService;
    @Autowired
    private WebApplicationContext webContext;
    @MockitoBean
    private IntegrationService integrationService;

    private Long userId;
    private Long workspaceId;
    private Long taskId;

    @BeforeEach
    void setUp() {
        userId = createUser("C-04 Test", "c04@test.example");
        workspaceId = createWorkspace(userId);
        taskId = taskService.createTask(workspaceId, new TaskCommand("발표 준비", null, userId, null,
            TaskStatus.IN_PROGRESS, List.of(new DeliverableInput("발표 자료", ".pdf"),
                new DeliverableInput("원본", "pptx")), null));
        when(integrationService.getConnectionTargets(workspaceId, Provider.GOOGLE_DRIVE)).thenReturn(List.of(FOLDER));
    }

    @Test
    void 검색한_팀_자료를_중복_없이_연결하고_해제한다() {
        Long slack = save(workspaceId, null, Provider.SLACK, ContentType.MESSAGE, "slack", "발표 논의", "channel");
        contentRefService.updateContent(slack, "#channel", "발표 논의");
        Long notion = save(workspaceId, null, Provider.NOTION, ContentType.PAGE, "notion", "요구사항", "notion-root");
        Long drive = driveFile(workspaceId, "drive", "발표 자료.pdf", FOLDER);

        assertThat(resources.getCandidates(workspaceId, userId, taskId, Provider.SLACK, "발표"))
            .extracting(TaskReferenceCandidate::contentRefId).containsExactly(slack);
        assertThat(resources.getCandidates(workspaceId, userId, taskId, Provider.NOTION, "없음")).isEmpty();
        assertThat(resources.getCandidates(workspaceId, userId, taskId, Provider.GOOGLE_DRIVE, "PDF"))
            .extracting(TaskReferenceCandidate::contentRefId).containsExactly(drive);

        resources.link(workspaceId, userId, taskId, slack);
        resources.link(workspaceId, userId, taskId, slack);
        resources.link(workspaceId, userId, taskId, notion);
        resources.link(workspaceId, userId, taskId, drive);
        assertThat(resources.getCandidates(workspaceId, userId, taskId, Provider.SLACK, "")).isEmpty();
        assertThat(taskContextService.getTaskContext(taskId).contentsByProvider().get(Provider.SLACK))
            .extracting(ContentRefDTO::contentRefId).containsExactly(slack);
        assertThat(taskContextService.getTasksByContents(List.of(notion)).get(notion))
            .extracting(TaskSummary::taskId).containsExactly(taskId);

        resources.unlink(workspaceId, userId, taskId, notion);
        assertThat(taskContextService.getTasksByContents(List.of(notion)).get(notion)).isEmpty();
    }

    @Test
    void 다른_워크스페이스_자료와_개인_자료를_연결할_수_없다() {
        Long otherWorkspace = createWorkspace(userId);
        Long foreign = driveFile(otherWorkspace, "foreign", "foreign.pdf", FOLDER);
        Long personal = save(workspaceId, userId, Provider.GMAIL, ContentType.MAIL, "mail", "내 메일", null);
        Long outside = driveFile(workspaceId, "outside", "outside.pdf", "unlinked-folder");
        assertFailure(() -> resources.link(workspaceId, userId, taskId, foreign), ErrorCode.RESOURCE_NOT_FOUND);
        assertFailure(() -> resources.link(workspaceId, userId, taskId, personal), ErrorCode.RESOURCE_NOT_FOUND);
        assertFailure(() -> resources.link(workspaceId, userId, taskId, outside), ErrorCode.RESOURCE_NOT_FOUND);
        assertFailure(() -> resources.link(otherWorkspace, userId, taskId, foreign), ErrorCode.TASK_NOT_FOUND);
    }

    @Test
    void 허용_확장자를_검사하고_제출과_해제에_따라_Task_상태를_갱신한다() {
        var deliverables = taskContextService.getTaskContext(taskId).deliverables();
        Long pdfSlot = deliverables.getFirst().deliverableId();
        Long slidesSlot = deliverables.getLast().deliverableId();
        Long pdf = driveFile(workspaceId, "pdf", "발표.PDF", FOLDER);
        Long slides = driveFile(workspaceId, "slides", "발표.pptx", FOLDER);
        Long otherWorkspace = createWorkspace(userId);
        Long sameDriveFileInOtherWorkspace = driveFile(otherWorkspace, "pdf", "발표.PDF", FOLDER);

        assertFailure(() -> resources.submit(workspaceId, userId, taskId, pdfSlot, slides),
            ErrorCode.DELIVERABLE_EXTENSION_MISMATCH);
        assertFailure(() -> resources.submit(workspaceId, userId, taskId, pdfSlot, sameDriveFileInOtherWorkspace),
            ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(taskService.getTask(taskId).progress()).isZero();

        resources.submit(workspaceId, userId, taskId, pdfSlot, pdf);
        assertThat(taskService.getTask(taskId).progress()).isEqualTo(50);
        assertThat(taskService.getTask(taskId).status()).isEqualTo(TaskStatus.IN_PROGRESS);
        resources.submit(workspaceId, userId, taskId, slidesSlot, slides);
        resources.submit(workspaceId, userId, taskId, slidesSlot, slides);
        assertThat(taskService.getTask(taskId).progress()).isEqualTo(100);
        assertThat(taskService.getTask(taskId).status()).isEqualTo(TaskStatus.DONE);

        var submitted = taskContextService.getTasksByContents(List.of(pdf, sameDriveFileInOtherWorkspace));
        assertThat(submitted.get(pdf)).extracting(TaskSummary::taskId).containsExactly(taskId);
        assertThat(submitted.get(sameDriveFileInOtherWorkspace)).isEmpty();

        resources.unsubmit(workspaceId, userId, taskId, pdfSlot);
        assertThat(taskService.getTask(taskId).status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(taskService.getTask(taskId).progress()).isEqualTo(50);
        assertThat(taskContextService.getTasksByContents(List.of(pdf)).get(pdf)).isEmpty();
        assertThat(contentRefService.getContentRef(pdf).externalId()).isEqualTo("pdf");
    }

    @Test
    void Task_자료_API는_인증과_입력을_검사하고_상세_응답에_변경을_반영한다() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        var session = new MockHttpSession(webContext.getServletContext());
        session.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, userId);
        String tasksPath = "/api/workspaces/" + workspaceId + "/tasks";
        String taskPath = tasksPath + "/" + taskId;
        Long pdf = driveFile(workspaceId, "api-pdf", "발표.pdf", FOLDER);
        Long slot = taskContextService.getTaskContext(taskId).deliverables().getFirst().deliverableId();
        String submission = taskPath + "/deliverables/" + slot + "/submission";

        mvc.perform(get(taskPath + "/references/candidates").param("provider", "GOOGLE_DRIVE"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(tasksPath).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"검증용 Task","deliverables":[{"name":" ","ext":"pdf"}]}
                    """))
            .andExpect(status().isBadRequest());
        mvc.perform(get(taskPath + "/references/candidates").session(session).param("provider", "GOOGLE_DRIVE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].contentRefId").value(pdf))
            .andExpect(jsonPath("$.data[0].content").doesNotExist());
        mvc.perform(put(taskPath + "/references/" + pdf).session(session))
            .andExpect(status().isNoContent());
        mvc.perform(put(submission).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentRefId\":" + pdf + "}"))
            .andExpect(status().isNoContent());
        mvc.perform(get(taskPath + "/context").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.task.progress").value(50))
            .andExpect(jsonPath("$.data.contentsByProvider.GOOGLE_DRIVE[0].contentRefId").value(pdf))
            .andExpect(jsonPath("$.data.deliverables[0].submitted").value(true));
        mvc.perform(delete(submission).session(session)).andExpect(status().isNoContent());
        mvc.perform(delete(taskPath + "/references/" + pdf).session(session)).andExpect(status().isNoContent());
        mvc.perform(get(taskPath + "/context").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.task.progress").value(0))
            .andExpect(jsonPath("$.data.contentsByProvider").isEmpty());
    }

    private Long driveFile(Long workspace, String origin, String title, String folder) {
        return save(workspace, null, Provider.GOOGLE_DRIVE, ContentType.FILE, origin, title, folder);
    }

    private Long save(Long workspace, Long owner, Provider provider, ContentType type,
                      String origin, String title, String channel) {
        return contentRefService.upsert(new ContentRefUpsert(workspace, owner, provider, type, origin, channel,
            null, title, title, "https://example.com/" + origin, LocalDateTime.now())).contentRefId();
    }

    private static void assertFailure(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
