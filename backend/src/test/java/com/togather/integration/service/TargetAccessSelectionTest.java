package com.togather.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.integration.domain.IntegrationTarget;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.repository.IntegrationTargetRepository;
import com.togather.integration.repository.ServiceConnectionRepository;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TargetAccessSelectionTest extends IntegrationTestSupport {
    @Autowired private IntegrationService integrations;
    @Autowired private ConnectionOperationRegistry operations;
    @Autowired private ServiceConnectionRepository connections;
    @Autowired private IntegrationTargetRepository targets;
    @Autowired private TokenCipher cipher;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.togather.integration.client.SlackOAuthClient slackOAuth;

    private Long userId;
    private Long otherId;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        userId = createUser("첫 연결", UUID.randomUUID() + "@test.example");
        otherId = createUser("대상 연결", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(userId);
        addMember(workspaceId, otherId, "MEMBER");
    }

    @Test
    void 먼저_연결한_사람이_아닌_원래_대상을_선택한_사람의_연결을_쓴다() {
        save(userId, "new-calendar", "first-token");
        ServiceConnection original = save(otherId, "original-calendar", "original-token");
        try (var scope = operations.openScope()) {
            var access = integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR, "original-calendar").orElseThrow();
            assertThat(access.connectionId()).isEqualTo(original.getConnectionId());
            assertThat(access.accessToken()).isEqualTo("original-token");
            assertThat(access.targetIds()).containsExactly("original-calendar");
        }
    }

    @Test
    void 개인_Slack_수집은_본인_연결만_사용하고_채널선택을_요구하지_않는다() {
        var connection = connections.save(new ServiceConnection(workspaceId, userId, Provider.SLACK, "T1:U1",
                cipher.encrypt("private-token", null), "im:read,im:history", LocalDateTime.now().plusDays(1)));
        try (var scope = operations.openScope()) {
            assertThat(integrations.findOwnAccess(workspaceId, otherId, Provider.SLACK)).isEmpty();
            var own = integrations.findOwnAccess(workspaceId, userId, Provider.SLACK).orElseThrow();
            assertThat(own.connectionId()).isEqualTo(connection.getConnectionId());
            assertThat(own.accessToken()).isEqualTo("private-token");
            assertThat(own.toString()).doesNotContain("private-token", "im:read");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> operations.beginDisconnect(connection))
                    .isInstanceOf(com.togather.common.exception.BusinessException.class);
        }
        connection.disconnect(); connections.save(connection);
        assertThat(integrations.findOwnAccess(workspaceId, userId, Provider.SLACK)).isEmpty();
    }

    @Test
    void 개인_토큰은_다른워크스페이스와_비멤버에게_주지_않는다() {
        Long outsider = createUser("외부", UUID.randomUUID() + "@test.example");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> integrations.findOwnAccess(workspaceId, outsider, Provider.SLACK))
                .isInstanceOf(com.togather.common.exception.BusinessException.class);
    }

    @Test
    void 대상이_없으면_다른_연결로_대체하지_않고_기존_두_인자_호출은_유지한다() {
        ServiceConnection first = save(userId, "current-calendar", "first-token");
        try (var scope = operations.openScope()) {
            assertThat(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR, "missing-calendar")).isEmpty();
            assertThat(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR).orElseThrow().connectionId())
                .isEqualTo(first.getConnectionId());
        }
    }

    @Test
    void 해제된_연결의_대상은_사용하지_않는다() {
        ServiceConnection original = save(userId, "original-calendar", "original-token");
        original.disconnect();
        connections.save(original);
        save(otherId, "other-calendar", "other-token");
        try (var scope = operations.openScope()) {
            assertThat(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR, "original-calendar")).isEmpty();
        }
    }

    @Test
    void 팀장이_아닌_팀원이_남의_연결_대상을_열면_500이_아니라_권한_오류로_알린다() {
        ServiceConnection ownersConnection = save(userId, "team-calendar", "owner-token");
        assertThatThrownBy(() -> integrations.getAvailableTargets(workspaceId, otherId, ownersConnection.getConnectionId()))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_OWNER_REQUIRED));
    }

    @Test
    void 내_선택_창에_보인_대상만_바꾸고_나에게_안_보이는_다른_사람의_대상은_그대로_둔다() {
        ServiceConnection mine = slack(userId);
        slack(otherId, "C-ALL", "C-SOCIAL", "C-NEW", "C-PRIVATE");
        // 내 Slack 계정으로 고를 수 있는 채널: C-PRIVATE는 내가 없는 비공개 채널이라 선택 창에 안 나온다.
        org.mockito.Mockito.when(slackOAuth.supports(Provider.SLACK)).thenReturn(true);
        org.mockito.Mockito.when(slackOAuth.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(slackOAuth.getAvailableTargets(org.mockito.ArgumentMatchers.eq(Provider.SLACK),
                org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(java.util.List.of(new com.togather.integration.client.OAuthProviderClient.TargetOption("C-ALL", "전체"),
                new com.togather.integration.client.OAuthProviderClient.TargetOption("C-SOCIAL", "소셜"),
                new com.togather.integration.client.OAuthProviderClient.TargetOption("C-NEW", "새-채널"),
                new com.togather.integration.client.OAuthProviderClient.TargetOption("C-TEST", "test")));
        try (var scope = operations.openScope()) {
            integrations.selectTargets(workspaceId, userId, mine.getConnectionId(), new com.togather.integration.dto.TargetSelectRequest(
                java.util.List.of(new com.togather.integration.dto.TargetSelectRequest.Target("C-ALL", "전체"),
                    new com.togather.integration.dto.TargetSelectRequest.Target("C-TEST", "test"))));
        }
        assertThat(integrations.getConnectionTargets(workspaceId, Provider.SLACK))
            .as("내가 해제한 소셜·새-채널은 빠지고, 내게 안 보이는 C-PRIVATE는 남는다")
            .containsExactlyInAnyOrder("C-ALL", "C-TEST", "C-PRIVATE");
    }

    @Test
    void 선택_가능한_대상을_확인하지_못하면_다른_사람의_대상은_지우지_않는다() {
        ServiceConnection mine = slack(userId);
        slack(otherId, "C-ALL", "C-SOCIAL");
        org.mockito.Mockito.when(slackOAuth.supports(Provider.SLACK)).thenReturn(true);
        org.mockito.Mockito.when(slackOAuth.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(slackOAuth.getAvailableTargets(org.mockito.ArgumentMatchers.eq(Provider.SLACK),
                org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));
        try (var scope = operations.openScope()) {
            integrations.selectTargets(workspaceId, userId, mine.getConnectionId(), new com.togather.integration.dto.TargetSelectRequest(
                java.util.List.of(new com.togather.integration.dto.TargetSelectRequest.Target("C-ALL", "전체"))));
        }
        assertThat(integrations.getConnectionTargets(workspaceId, Provider.SLACK)).containsExactlyInAnyOrder("C-ALL", "C-SOCIAL");
    }

    private ServiceConnection slack(Long owner, String... channelIds) {
        var connection = new ServiceConnection(workspaceId, owner, Provider.SLACK, UUID.randomUUID().toString(),
            cipher.encrypt("slack-" + owner, "test-refresh"), "test-scope", LocalDateTime.now().plusDays(1));
        connection.changeStatus(ConnectionStatus.CONNECTED);
        connection = connections.save(connection);
        for (String id : channelIds) {
            targets.save(new IntegrationTarget(connection.getConnectionId(), id, id));
        }
        return connection;
    }

    private ServiceConnection save(Long owner, String calendarId, String token) {
        var connection = new ServiceConnection(workspaceId, owner, Provider.GOOGLE_CALENDAR, UUID.randomUUID().toString(),
            cipher.encrypt(token, "test-refresh"), "test-scope", LocalDateTime.now().plusDays(1));
        connection.changeStatus(ConnectionStatus.CONNECTED);
        connection = connections.save(connection);
        targets.save(new IntegrationTarget(connection.getConnectionId(), calendarId, calendarId));
        return connection;
    }
}
