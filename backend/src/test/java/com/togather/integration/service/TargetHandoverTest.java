package com.togather.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.togather.auth.dto.OAuthToken;
import com.togather.auth.service.OAuthStateStore.OAuthState;
import com.togather.auth.service.OAuthStateStore.Purpose;
import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.integration.client.OAuthProviderClient.AuthResult;
import com.togather.integration.client.OAuthProviderClient.TargetOption;
import com.togather.integration.client.SlackOAuthClient;
import com.togather.integration.domain.IntegrationTarget;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.dto.IntegrationStatusResponse;
import com.togather.integration.repository.IntegrationTargetRepository;
import com.togather.integration.repository.ServiceConnectionRepository;
import com.togather.support.IntegrationTestSupport;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 연결이 끊기거나 재인증이 필요해져도 팀이 고른 채널을 잃지 않는다. (볼 수 있는 팀원에게 넘기고, 없으면 보관했다가 되살린다) */
class TargetHandoverTest extends IntegrationTestSupport {

    @Autowired private IntegrationService integrations;
    @Autowired private ServiceConnectionRepository connections;
    @Autowired private IntegrationTargetRepository targets;
    @Autowired private TokenCipher cipher;
    @MockitoBean private SlackOAuthClient slack;

    private Long leader;
    private Long member;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        leader = createUser("팀장", UUID.randomUUID() + "@test.example");
        member = createUser("팀원", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(leader);
        addMember(workspaceId, member, "MEMBER");
        when(slack.supports(Provider.SLACK)).thenReturn(true);
        when(slack.isConfigured()).thenReturn(true);
        when(slack.appKey()).thenReturn("slack");
        when(slack.getAvailableTargets(eq(Provider.SLACK), anyString()))
            .thenAnswer(call -> visible.getOrDefault(call.<String>getArgument(1), List.of()));
    }

    @Test
    void 팀장이_연결을_해제하면_채널을_볼_수_있는_팀원_연결로_넘긴다() {
        ServiceConnection leaders = slackConnection(leader, "leader-token", "C-TEAM");
        ServiceConnection members = slackConnection(member, "member-token");
        canSee("member-token", "C-TEAM");

        integrations.disconnectService(workspaceId, leader, leaders.getConnectionId());

        assertThat(names(members)).containsExactly("C-TEAM");
        assertThat(integrations.getConnectionTargets(workspaceId, Provider.SLACK)).containsExactly("C-TEAM");
        assertThat(slackStatus().parkedTargets()).isEmpty();
    }

    @Test
    void 직접_연결을_해제하면_아무도_볼_수_없는_채널은_보관하지_않고_지워서_경고가_뜨지_않는다() {
        ServiceConnection leaders = slackConnection(leader, "leader-token", "C-TEAM");
        slackConnection(member, "member-token");
        canSee("member-token");

        integrations.disconnectService(workspaceId, leader, leaders.getConnectionId());

        assertThat(integrations.getConnectionTargets(workspaceId, Provider.SLACK)).isEmpty();
        assertThat(slackStatus().parkedTargets()).isEmpty();
        assertThat(targets.findAllByConnectionId(leaders.getConnectionId())).isEmpty();
    }

    @Test
    void 연결은_그대로인데_Slack에서_권한이_끊겨_아무도_못_보면_경고로_알리고_다시_인증하면_되살아난다() {
        ServiceConnection leaders = slackConnection(leader, "leader-token", "C-TEAM");
        slackConnection(member, "member-token");
        canSee("member-token");

        integrations.reportUnauthorized(leaders.getConnectionId());

        assertThat(slackStatus().parkedTargets()).containsExactly("C-TEAM");

        // 같은 계정으로 다시 인증하면 채널이 그대로 돌아온다.
        when(slack.exchangeCode(eq(Provider.SLACK), anyString(), anyString())).thenReturn(new AuthResult(
            new OAuthToken("leader-token-2", null, null, "scope"),
            connections.findById(leaders.getConnectionId()).orElseThrow().getExternalAccountId(), "팀 Slack"));
        canSee("leader-token-2", "C-TEAM");
        Long reconnected = integrations.completeConnection(new OAuthState(Purpose.REAUTH, workspaceId, leader,
            leaders.getConnectionId(), Provider.SLACK.name(), "/", Instant.now()), "code");

        assertThat(targets.findAllByConnectionId(reconnected)).extracting(IntegrationTarget::getExternalTargetId)
            .containsExactly("C-TEAM");
        assertThat(slackStatus().parkedTargets()).isEmpty();
        assertThat(connections.findById(reconnected).orElseThrow().getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    void 외부에서_권한이_끊기면_바로_재인증_필요로_표시하고_채널은_팀원에게_넘긴다() {
        ServiceConnection leaders = slackConnection(leader, "leader-token", "C-TEAM");
        ServiceConnection members = slackConnection(member, "member-token");
        canSee("member-token", "C-TEAM");

        assertThat(integrations.reportUnauthorized(leaders.getConnectionId())).isTrue();

        assertThat(connections.findById(leaders.getConnectionId()).orElseThrow().getStatus())
            .isEqualTo(ConnectionStatus.REAUTH_REQUIRED);
        assertThat(names(members)).containsExactly("C-TEAM");
        assertThat(integrations.reportUnauthorized(leaders.getConnectionId())).as("이미 표시됨").isFalse();
    }

    @Test
    void 토큰을_갱신할_수_있으면_재인증_필요로_바꾸지_않는다() {
        ServiceConnection leaders = slackConnection(leader, "leader-token", "C-TEAM");
        var withRefresh = connections.findById(leaders.getConnectionId()).orElseThrow();
        withRefresh.refreshTokens(cipher.encrypt("leader-token", "refresh"), "scope", LocalDateTime.now().plusDays(1));
        connections.save(withRefresh);
        when(slack.refresh("refresh")).thenReturn(new OAuthToken("fresh-token", "refresh", 3600L, "scope"));

        assertThat(integrations.reportUnauthorized(leaders.getConnectionId())).isFalse();
        assertThat(connections.findById(leaders.getConnectionId()).orElseThrow().getStatus())
            .isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    void 원본에서_지운_대상은_권한이_끊긴_연결에_남은_것까지_함께_빼서_경고에_남지_않는다() {
        ServiceConnection leaders = slackConnection(leader, "leader-token", "C-GONE", "C-KEEP");
        ServiceConnection members = slackConnection(member, "member-token", "C-GONE-2");
        canSee("member-token");
        integrations.reportUnauthorized(leaders.getConnectionId());
        assertThat(slackStatus().parkedTargets()).containsExactlyInAnyOrder("C-GONE", "C-KEEP");

        integrations.removeDeletedTargets(workspaceId, Provider.SLACK, List.of("C-GONE", "C-GONE-2"));

        assertThat(slackStatus().parkedTargets()).containsExactly("C-KEEP");
        assertThat(names(members)).isEmpty();
    }

    @Test
    void 외부_계정으로_찾을_때도_권한이_끊긴_연결의_대상까지_뺀다() {
        ServiceConnection leaders = slackConnection(leader, "leader-token", "C-GONE");
        slackConnection(member, "member-token");
        canSee("member-token");
        integrations.reportUnauthorized(leaders.getConnectionId());
        assertThat(slackStatus().parkedTargets()).containsExactly("C-GONE");
        String account = connections.findById(leaders.getConnectionId()).orElseThrow().getExternalAccountId();

        integrations.removeDeletedTargetsByExternalAccount(Provider.SLACK, account, List.of("C-GONE"));

        assertThat(slackStatus().parkedTargets()).isEmpty();
    }

    /** 이 토큰(Slack 계정)으로 선택 창에 보이는 채널 */
    private final java.util.Map<String, List<TargetOption>> visible = new java.util.HashMap<>();

    private void canSee(String token, String... channelIds) {
        visible.put(token, java.util.Arrays.stream(channelIds).map(id -> new TargetOption(id, id)).toList());
    }

    private List<String> names(ServiceConnection connection) {
        return targets.findAllByConnectionId(connection.getConnectionId()).stream()
            .map(IntegrationTarget::getExternalTargetId).toList();
    }

    private IntegrationStatusResponse slackStatus() {
        return integrations.getConnectionStatuses(workspaceId, leader).stream()
            .filter(i -> i.provider() == Provider.SLACK).findFirst().orElseThrow();
    }

    private ServiceConnection slackConnection(Long owner, String token, String... channelIds) {
        var connection = new ServiceConnection(workspaceId, owner, Provider.SLACK, "T1:" + UUID.randomUUID(),
            cipher.encrypt(token, null), "scope", LocalDateTime.now().plusDays(1));
        connection.changeStatus(ConnectionStatus.CONNECTED);
        connection = connections.save(connection);
        for (String id : channelIds) {
            targets.save(new IntegrationTarget(connection.getConnectionId(), id, id));
        }
        return connection;
    }
}
