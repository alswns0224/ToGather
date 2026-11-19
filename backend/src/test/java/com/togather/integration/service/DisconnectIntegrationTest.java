package com.togather.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.togather.common.auth.LoginUserArgumentResolver;
import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveFile;
import com.togather.integration.client.GoogleIntegrationClient;
import com.togather.integration.controller.ConnectionOperationFilter;
import com.togather.integration.domain.IntegrationTarget;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.repository.IntegrationTargetRepository;
import com.togather.integration.repository.ServiceConnectionRepository;
import com.togather.support.IntegrationTestSupport;
import com.togather.sync.service.SyncAdapter;
import com.togather.sync.service.SyncService;
import com.togather.sync.service.SyncStateService;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class DisconnectIntegrationTest extends IntegrationTestSupport {
    @Autowired private IntegrationService integrations;
    @Autowired private ConnectionOperationRegistry operations;
    @Autowired private ConnectionOperationFilter operationFilter;
    @Autowired private TokenCipher cipher;
    @Autowired private ServiceConnectionRepository connections;
    @Autowired private IntegrationTargetRepository targets;
    @Autowired private SyncStateService syncStates;
    @Autowired private WorkspaceEventService events;
    @Autowired private WebApplicationContext context;
    @MockitoBean private GoogleIntegrationClient oauthClient;
    @MockitoBean private DriveApiClient driveClient;

    private Long userId;
    private Long workspaceId;
    private ServiceConnection connection;
    private String folder;
    private MockMvc mvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        userId = createUser("연결 해제 검증", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(userId);
        folder = "folder-" + UUID.randomUUID();
        connection = saveConnection(workspaceId, userId, Provider.GOOGLE_DRIVE, UUID.randomUUID().toString());
        targets.save(new IntegrationTarget(connection.getConnectionId(), folder, "테스트 폴더"));
        when(oauthClient.supports(Provider.GOOGLE_DRIVE)).thenReturn(true);
        when(oauthClient.supports(Provider.GMAIL)).thenReturn(true);
        when(oauthClient.isConfigured()).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(operationFilter).build();
        session = new MockHttpSession(context.getServletContext());
        session.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, userId);
    }

    @Test
    void 업로드_중_해제는_거절하고_업로드_완료_후에는_해제한다() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(driveClient.uploadFile(anyString(), anyString(), anyString(), anyString(), any(Path.class)))
            .thenAnswer(call -> {
                entered.countDown();
                assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
                return uploaded();
            });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var upload = executor.submit(() -> mvc.perform(multipart(
                    "/api/workspaces/" + workspaceId + "/drive/files")
                    .file(new MockMultipartFile("file", "test.txt", "text/plain", "test".getBytes()))
                    .param("folderId", folder).session(session)).andReturn());
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONNECTION_IN_USE"));
                assertConnected();
                verify(oauthClient, never()).revoke(anyString());
            } finally {
                finish.countDown();
            }
            assertThat(upload.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        }
        mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manualRevocationRequired").value(false));
        assertDisconnected();
    }

    @Test
    void 업로드가_실패해도_사용중_표시가_남지_않는다() throws Exception {
        when(driveClient.uploadFile(anyString(), anyString(), anyString(), anyString(), any(Path.class)))
            .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));
        mvc.perform(multipart("/api/workspaces/" + workspaceId + "/drive/files")
                .file(new MockMultipartFile("file", "test.txt", "text/plain", "test".getBytes()))
                .param("folderId", folder).session(session)).andExpect(status().isBadGateway());
        mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isOk());
        assertDisconnected();
    }

    @Test
    void 백그라운드_동기화도_해제로부터_보호하고_끝나면_반납한다() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        SyncService sync = syncService(() -> {
            entered.countDown();
            try {
                assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> sync.syncNow(workspaceId, Provider.GOOGLE_DRIVE, userId, true));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isConflict());
                assertConnected();
            } finally {
                finish.countDown();
            }
            assertThat(running.get(10, TimeUnit.SECONDS)).allMatch(result -> !result.failed());
        }
        mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isOk());
    }

    @Test
    void 동기화_실패_후에도_연결을_해제할_수_있다() throws Exception {
        var sync = syncService(() -> { throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR); });
        assertThat(sync.syncNow(workspaceId, Provider.GOOGLE_DRIVE, userId, true))
            .allMatch(result -> result.failed());
        mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isOk());
    }

    @Test
    void 해제가_시작된_뒤에는_새_작업에_토큰을_주지_않는다() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(oauthClient).revoke(anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var disconnecting = executor.submit(() -> mvc.perform(delete(disconnectPath()).session(session)).andReturn());
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                try (var scope = operations.openScope()) {
                    assertThatThrownBy(() -> integrations.getAccessToken(workspaceId, userId, Provider.GOOGLE_DRIVE))
                        .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_IN_USE));
                }
            } finally {
                finish.countDown();
            }
            assertThat(disconnecting.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        // 해제 전 읽어 둔 Entity로도 이미 폐기된 토큰을 다시 받을 수 없다.
        assertThatThrownBy(() -> integrations.validAccessToken(connection))
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND));
    }

    @Test
    void 다른_계정은_해제하고_같은_Google_계정의_Gmail_해제는_막는다() throws Exception {
        Long otherUser = createUser("다른 계정", UUID.randomUUID() + "@test.example");
        addMember(workspaceId, otherUser, "MEMBER");
        var other = saveConnection(workspaceId, otherUser, Provider.GOOGLE_DRIVE, UUID.randomUUID().toString());
        var gmail = saveConnection(workspaceId, userId, Provider.GMAIL, connection.getExternalAccountId());
        try (var scope = operations.openScope()) {
            integrations.getAccessToken(workspaceId, userId, Provider.GOOGLE_DRIVE);
            assertThat(integrations.disconnectService(workspaceId, userId, other.getConnectionId())
                .manualRevocationRequired()).isFalse();
            assertThatThrownBy(() -> integrations.disconnectService(workspaceId, userId, gmail.getConnectionId()))
                .isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_IN_USE));
        }
        assertConnected();
    }

    @Test
    void Google_폐기_후_같은_계정만_재인증_표시하고_이전_토큰도_반환하지_않는다() throws Exception {
        var gmail = saveConnection(workspaceId, userId, Provider.GMAIL, connection.getExternalAccountId());
        Long otherUser = createUser("다른 Google 사용자", UUID.randomUUID() + "@test.example");
        addMember(workspaceId, otherUser, "MEMBER");
        var other = saveConnection(workspaceId, otherUser, Provider.GMAIL, UUID.randomUUID().toString());
        mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.relatedConnectionsRequireReauth").value(true));
        assertThat(connections.findById(gmail.getConnectionId()).orElseThrow().getStatus())
            .isEqualTo(ConnectionStatus.REAUTH_REQUIRED);
        assertThat(connections.findById(other.getConnectionId()).orElseThrow().getStatus())
            .isEqualTo(ConnectionStatus.CONNECTED);
        assertThatThrownBy(() -> integrations.validAccessToken(gmail))
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REAUTH_REQUIRED));
    }

    @Test
    void 권한_폐기_실패는_로컬_해제를_유지하면서_별도로_안내한다() throws Exception {
        doThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR)).when(oauthClient).revoke(anyString());
        mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manualRevocationRequired").value(true));
        assertDisconnected();
        // 재요청은 빈 토큰을 복호화하거나 다시 폐기하지 않는다.
        mvc.perform(delete(disconnectPath()).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manualRevocationRequired").value(true));
        verify(oauthClient, times(1)).revoke(anyString());
    }

    @Test
    void 해제는_로그인_멤버십_연결_소속과_관리_권한을_검사한다() throws Exception {
        mvc.perform(delete(disconnectPath())).andExpect(status().isUnauthorized());
        Long member = createUser("팀원", UUID.randomUUID() + "@test.example");
        addMember(workspaceId, member, "MEMBER");
        var memberSession = new MockHttpSession(context.getServletContext());
        memberSession.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, member);
        mvc.perform(delete(disconnectPath()).session(memberSession)).andExpect(status().isForbidden());
        Long otherWorkspace = createWorkspace(userId);
        mvc.perform(delete("/api/workspaces/" + otherWorkspace + "/integrations/connections/"
                + connection.getConnectionId()).session(session)).andExpect(status().isNotFound());
        assertConnected();
        verify(oauthClient, never()).revoke(anyString());
    }

    private SyncService syncService(Runnable work) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("testAdapter", new SyncAdapter() {
            @Override public Provider provider() { return Provider.GOOGLE_DRIVE; }
            @Override public List<SyncTarget> findTargets(Long workspace, Long requestedBy) {
                return List.of(SyncTarget.team(folder));
            }
            @Override public SyncResult sync(SyncContext sync) {
                integrations.getAccessToken(workspaceId, userId, Provider.GOOGLE_DRIVE);
                work.run();
                return new SyncResult(0, LocalDateTime.now());
            }
        });
        return new SyncService(factory.getBeanProvider(SyncAdapter.class), syncStates, events, operations);
    }

    private ServiceConnection saveConnection(Long workspace, Long user, Provider provider, String account) {
        var saved = new ServiceConnection(workspace, user, provider, account,
            cipher.encrypt("test-token", "test-refresh"), "test-scope", LocalDateTime.now().plusDays(1));
        saved.changeStatus(ConnectionStatus.CONNECTED);
        return connections.save(saved);
    }

    private DriveFile uploaded() {
        return new DriveFile("uploaded-" + UUID.randomUUID(), "test.txt", "text/plain", null,
            "https://example.com/file", LocalDateTime.now(), LocalDateTime.now(), List.of(folder), false);
    }

    private String disconnectPath() {
        return "/api/workspaces/" + workspaceId + "/integrations/connections/" + connection.getConnectionId();
    }

    private void assertConnected() {
        assertThat(connections.findById(connection.getConnectionId()).orElseThrow().getStatus())
            .isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(targets.findAllByConnectionId(connection.getConnectionId())).hasSize(1);
    }

    private void assertDisconnected() {
        var saved = connections.findById(connection.getConnectionId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(saved.getEncryptedTokens()).isEmpty();
        assertThat(targets.findAllByConnectionId(connection.getConnectionId())).isEmpty();
    }
}
