package com.togather.content.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.togather.common.auth.LoginUserArgumentResolver;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.enums.TaskStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveFile;
import com.togather.content.drive.client.DriveApiClient.DriveChange;
import com.togather.content.drive.client.DriveApiClient.ChangeResult;
import com.togather.content.drive.dto.DriveUploadMode;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.service.TeamTokenResolver;
import com.togather.integration.service.IntegrationService;
import com.togather.support.IntegrationTestSupport;
import com.togather.sync.service.SyncAdapter.SyncContext;
import com.togather.task.dto.DeliverableInput;
import com.togather.task.dto.TaskCommand;
import com.togather.task.service.TaskService;
import com.togather.task.service.TaskResourceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class DriveOverwriteTest extends IntegrationTestSupport {
    private static final Provider DRIVE = Provider.GOOGLE_DRIVE;
    private static final String FOLDER = "overwrite-folder";
    private static final String TOKEN = "test-token";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 20, 10, 0);
    @Autowired private DriveContentService drive;
    @Autowired private DriveSyncAdapter sync;
    @Autowired private ContentRefService contents;
    @Autowired private TaskService tasks;
    @Autowired private TaskResourceService resources;
    @Autowired private WebApplicationContext context;
    @MockitoBean private DriveApiClient driveApiClient;
    @MockitoBean private IntegrationService integrationService;
    @MockitoBean private TeamTokenResolver teamTokenResolver;
    @TempDir Path temp;
    private Long workspaceId;
    private Long userId;
    private Long originalRef;
    private Path payload;
    private MockMvc mvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        userId = createUser("Drive 저장 검증", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(userId);
        when(integrationService.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(FOLDER));
        when(teamTokenResolver.tokenFor(workspaceId, DRIVE, FOLDER, userId)).thenReturn(TOKEN);
        when(driveApiClient.getFileMetadata(TOKEN, "original-file")).thenReturn(file("original-file", "1", 5000000000L));
        when(driveApiClient.findFilesByName(TOKEN, FOLDER, "result.pdf"))
            .thenReturn(List.of(file("original-file", "1", 5000000000L)));
        when(driveApiClient.overwriteFile(eq(TOKEN), eq("original-file"), eq("result.pdf"), any(), any(Path.class)))
            .thenReturn(file("original-file", "2", 3L));
        when(driveApiClient.uploadFile(eq(TOKEN), eq(FOLDER), eq("result.pdf"), any(), any(Path.class)))
            .thenReturn(file("new-file", "1", 3L));
        originalRef = contents.upsertDriveFile(command("original-file", "result.pdf", "파일 유형: application/pdf"), 5000000000L).contentRefId();
        payload = Files.write(temp.resolve("payload"), new byte[] {1, 2, 3});
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        session = sessionFor(userId);
    }

    @Test
    void 같은_이름을_확인하고_명시적_선택_없이_업로드하면_충돌로_중단한다() throws Exception {
        mvc.perform(get(base() + "/upload-candidates").session(session).param("folderId", FOLDER).param("name", "result.pdf"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].fileId").value("original-file"))
            .andExpect(jsonPath("$.data[0].fileSize").value(5000000000L))
            .andExpect(jsonPath("$.data[0].version").value("1"))
            .andExpect(jsonPath("$.data[0].overwriteAllowed").value(true));
        mvc.perform(upload().param("mode", "ASK"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DRIVE_FILE_NAME_CONFLICT"));
        verify(driveApiClient, never()).uploadFile(anyString(), anyString(), anyString(), any(), any(Path.class));
        verify(driveApiClient, never()).overwriteFile(anyString(), anyString(), anyString(), any(), any(Path.class));
        assertThat(contents.getContentRef(originalRef).fileSize()).isEqualTo(5000000000L);
    }

    @Test
    void 덮어쓰기는_원본_ID와_콘텐츠_참조와_Task_연결과_제출을_유지한다() throws Exception {
        Long taskId = tasks.createTask(workspaceId, new TaskCommand("제출 보존", null, userId, null,
            TaskStatus.IN_PROGRESS, List.of(new DeliverableInput("결과", "pdf")), null));
        Long slot = jdbc.queryForObject("SELECT deliverable_id FROM task_deliverable WHERE task_id = ?", Long.class, taskId);
        resources.link(workspaceId, userId, taskId, originalRef);
        resources.submit(workspaceId, userId, taskId, slot, originalRef);
        mvc.perform(upload().param("mode", "OVERWRITE").param("targetFileId", "original-file").param("expectedVersion", "1"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.contentRefId").value(originalRef))
            .andExpect(jsonPath("$.data.externalId").value("original-file"))
            .andExpect(jsonPath("$.data.fileSize").value(3));
        assertThat(tasks.getTask(taskId).status()).isEqualTo(TaskStatus.DONE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_content_link WHERE task_id = ? AND content_ref_id = ?",
            Long.class, taskId, originalRef)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT file_id FROM task_deliverable WHERE deliverable_id = ?", String.class, slot)).isEqualTo("original-file");
        assertThat(contents.getContentRef(originalRef).fileSize()).isEqualTo(3L);
        verify(driveApiClient, never()).uploadFile(anyString(), anyString(), anyString(), any(), any(Path.class));
        verify(driveApiClient, never()).trashFile(anyString(), anyString());
    }

    @Test
    void 새_파일_저장은_기존_파일과_참조를_바꾸지_않고_새_ID를_만든다() throws Exception {
        mvc.perform(upload().param("mode", "CREATE_NEW")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.externalId").value("new-file")).andExpect(jsonPath("$.data.fileSize").value(3));
        assertThat(contents.getContentRef(originalRef).fileSize()).isEqualTo(5000000000L);
        assertThat(contents.findByOrigin(workspaceId, DRIVE, "new-file").orElseThrow().contentRefId()).isNotEqualTo(originalRef);
        verify(driveApiClient, never()).overwriteFile(anyString(), anyString(), anyString(), any(), any(Path.class));
    }

    @Test
    void 기존_업로드_호출은_새_파일_저장_방식을_유지한다() throws Exception {
        mvc.perform(upload()).andExpect(status().isCreated()).andExpect(jsonPath("$.data.externalId").value("new-file"));
        verify(driveApiClient, never()).findFilesByName(anyString(), anyString(), anyString());
    }

    @Test
    void 선택_후_원본이_변경되면_덮어쓰지_않고_재확인을_요청한다() throws Exception {
        when(driveApiClient.getFileMetadata(TOKEN, "original-file")).thenReturn(file("original-file", "2", 7L));
        mvc.perform(upload().param("mode", "OVERWRITE").param("targetFileId", "original-file").param("expectedVersion", "1"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DRIVE_FILE_CHANGED"));
        verify(driveApiClient, never()).overwriteFile(anyString(), anyString(), anyString(), any(), any(Path.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"moved", "trashed", "renamed", "native", "readonly"})
    void 이동_휴지통_이름변경_네이티브문서_권한없음은_덮어쓰지_않는다(String state) throws Exception {
        DriveFile target = new DriveFile("original-file", state.equals("renamed") ? "other.pdf" : "result.pdf",
            state.equals("native") ? "application/vnd.google-apps.document" : "application/pdf", null, null,
            CREATED, CREATED, List.of(state.equals("moved") ? "outside-folder" : FOLDER), state.equals("trashed"),
            5L, "1", !state.equals("readonly"));
        when(driveApiClient.getFileMetadata(TOKEN, "original-file")).thenReturn(target);
        int expected = state.equals("moved") || state.equals("trashed") ? 404 : state.equals("renamed") ? 409 : 400;
        mvc.perform(upload().param("mode", "OVERWRITE").param("targetFileId", "original-file").param("expectedVersion", "1"))
            .andExpect(status().is(expected));
        verify(driveApiClient, never()).overwriteFile(anyString(), anyString(), anyString(), any(), any(Path.class));
    }

    @Test
    void 덮어쓰기_실패_후에는_예약을_해제해_다시_시도할_수_있다() {
        when(driveApiClient.overwriteFile(eq(TOKEN), eq("original-file"), eq("result.pdf"), any(), any(Path.class)))
            .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR)).thenReturn(file("original-file", "2", 3L));
        assertThatThrownBy(this::overwrite).isInstanceOf(BusinessException.class);
        assertThat(overwrite().contentRefId()).isEqualTo(originalRef);
    }

    @Test
    void 같은_파일의_동시_업로드는_하나만_처리하고_다른_요청은_충돌을_반환한다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(driveApiClient.overwriteFile(eq(TOKEN), eq("original-file"), eq("result.pdf"), any(), any(Path.class)))
            .thenAnswer(call -> { started.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("대기 초과"); return file("original-file", "2", 3L); });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(this::overwrite);
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(this::overwrite).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DRIVE_UPLOAD_IN_PROGRESS));
                release.countDown(); first.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        verify(driveApiClient, times(1)).overwriteFile(eq(TOKEN), eq("original-file"), eq("result.pdf"), any(), any(Path.class));
    }

    @Test
    void 크기는_본문_제거_목록과_상세에_남고_0과_모름을_구분한다() throws Exception {
        contents.upsertDriveFile(command("original-file", "result.pdf", "파일 유형: application/pdf\n\n본문:\n비공개 본문"), 0L);
        mvc.perform(get(base() + "/files").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].fileSize").value(0))
            .andExpect(jsonPath("$.data[0].content").value("파일 유형: application/pdf"));
        contents.upsert(command("original-file", "result.pdf", "파일 유형: application/pdf"));
        assertThat(contents.getContentRef(originalRef).fileSize()).isZero();
        contents.upsertDriveFile(command("original-file", "result.pdf", "파일 유형: application/pdf"), null);
        mvc.perform(get(base() + "/files/" + originalRef).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fileSize").isEmpty());
    }

    @Test
    void 전체와_증분_동기화는_파일_크기를_저장하고_크기만_바뀌어도_변경으로_집계한다() {
        contents.upsertDriveFile(command("original-file", "result.pdf", "파일 유형: application/pdf\n\n본문:\n이미 추출한 본문"), 5000000000L);
        DriveFile synced = new DriveFile("synced-file", "data.bin", "application/octet-stream", null, null,
            CREATED, CREATED, List.of(FOLDER), false, 12L, "1", true);
        when(driveApiClient.getStartPageToken(TOKEN)).thenReturn("cursor-1");
        when(driveApiClient.listFolderFiles(TOKEN, FOLDER)).thenReturn(List.of(synced));
        sync.sync(new SyncContext(workspaceId, FOLDER, null, null, null, userId));
        assertThat(contents.findByOrigin(workspaceId, DRIVE, "synced-file").orElseThrow().fileSize()).isEqualTo(12L);
        DriveFile changed = new DriveFile("synced-file", "data.bin", "application/octet-stream", null, null,
            CREATED, CREATED, List.of(FOLDER), false, 0L, "2", true);
        when(driveApiClient.listChanges(TOKEN, "cursor-1")).thenReturn(new ChangeResult(List.of(new DriveChange("synced-file", false, changed)), "cursor-2"));
        assertThat(ContentRefService.countChanges(() -> sync.sync(new SyncContext(workspaceId, FOLDER, null, null, "cursor-1", userId)))).isEqualTo(1);
        assertThat(contents.findByOrigin(workspaceId, DRIVE, "synced-file").orElseThrow().fileSize()).isZero();
    }

    @Test
    void 후보_조회와_업로드는_인증_멤버십_선택폴더를_검사한다() throws Exception {
        Long outsider = createUser("외부인", UUID.randomUUID() + "@test.example");
        mvc.perform(get(base() + "/upload-candidates").param("folderId", FOLDER).param("name", "result.pdf"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(base() + "/upload-candidates").session(sessionFor(outsider)).param("folderId", FOLDER).param("name", "result.pdf"))
            .andExpect(status().isForbidden());
        mvc.perform(get(base() + "/upload-candidates").session(session).param("folderId", "not-selected").param("name", "result.pdf"))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart(base() + "/files").file(part()).param("folderId", FOLDER)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(base() + "/files").file(part()).session(sessionFor(outsider)).param("folderId", FOLDER)).andExpect(status().isForbidden());
    }

    @Test
    void 누락되거나_상충하는_업로드_선택은_거절한다() throws Exception {
        mvc.perform(upload().param("mode", "OVERWRITE")).andExpect(status().isBadRequest());
        mvc.perform(upload().param("mode", "OVERWRITE").param("targetFileId", "original-file").param("expectedVersion", "not-a-version"))
            .andExpect(status().isBadRequest());
        mvc.perform(upload().param("mode", "CREATE_NEW").param("targetFileId", "original-file")).andExpect(status().isBadRequest());
        mvc.perform(upload().param("mode", "UNKNOWN")).andExpect(status().isBadRequest());
    }

    private com.togather.content.dto.ContentRefDTO overwrite() {
        return drive.uploadFile(workspaceId, userId, FOLDER, "result.pdf", "application/pdf", payload,
            DriveUploadMode.OVERWRITE, "original-file", "1");
    }
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload() {
        return multipart(base() + "/files").file(part()).session(session).param("folderId", FOLDER);
    }
    private MockMultipartFile part() { return new MockMultipartFile("file", "result.pdf", "application/pdf", new byte[] {1, 2, 3}); }
    private static DriveFile file(String id, String version, Long size) {
        return new DriveFile(id, "result.pdf", "application/pdf", "메타데이터", "https://drive.example/" + id,
            CREATED, CREATED.plusMinutes(Long.parseLong(version)), List.of(FOLDER), false, size, version, true);
    }
    private ContentRefUpsert command(String id, String title, String body) {
        return new ContentRefUpsert(workspaceId, null, DRIVE, ContentType.FILE, id, FOLDER, null, title, body,
            "https://drive.example/" + id, CREATED, CREATED, null, null);
    }
    private MockHttpSession sessionFor(Long id) { var result = new MockHttpSession(context.getServletContext()); result.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, id); return result; }
    private String base() { return "/api/workspaces/" + workspaceId + "/drive"; }
}
