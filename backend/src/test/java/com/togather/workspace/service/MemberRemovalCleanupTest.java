package com.togather.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.client.GoogleIntegrationClient;
import com.togather.integration.client.NotionOAuthClient;
import com.togather.integration.client.SlackOAuthClient;
import com.togather.integration.domain.IntegrationTarget;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.repository.IntegrationTargetRepository;
import com.togather.integration.repository.ServiceConnectionRepository;
import com.togather.integration.service.TokenCipher;
import com.togather.support.IntegrationTestSupport;
import com.togather.sync.service.SyncStateService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("팀원을 내보내면 그 사람의 연동과 개인 자료를 지운다")
class MemberRemovalCleanupTest extends IntegrationTestSupport {

    // 외부 토큰 폐기 호출을 실제로 보내지 않는다.
    @MockitoBean private SlackOAuthClient slackOAuth;
    @MockitoBean private NotionOAuthClient notionOAuth;
    @MockitoBean private GoogleIntegrationClient googleOAuth;

    @Autowired private WorkspaceMemberService members;
    @Autowired private ServiceConnectionRepository connections;
    @Autowired private IntegrationTargetRepository targets;
    @Autowired private TokenCipher cipher;
    @Autowired private ContentRefService contents;
    @Autowired private SyncStateService syncStates;

    private Long owner;
    private Long leaving;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        owner = createUser("팀장", UUID.randomUUID() + "@test.example");
        leaving = createUser("내보낼 팀원", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(owner);
        addMember(workspaceId, leaving, "MEMBER");
    }

    @Test
    void 연동과_개인_자료와_후보를_지우고_팀_자료는_남긴다() {
        // 내보낼 팀원이 팀 Slack 채널을 연동 대상으로 골랐고, Gmail도 연결했다. 팀장도 Slack을 연결했다.
        ServiceConnection leavingSlack = connect(leaving, Provider.SLACK, "C-TEAM");
        connect(leaving, Provider.GMAIL);
        ServiceConnection ownerSlack = connect(owner, Provider.SLACK);

        ContentRefDTO teamMessage = content(null, Provider.SLACK, ContentType.MESSAGE, "C-TEAM", "팀 채널 대화");
        ContentRefDTO dm = content(leaving, Provider.SLACK, ContentType.MESSAGE, "D1", "나에게 온 DM");
        ContentRefDTO mail = content(leaving, Provider.GMAIL, ContentType.MAIL, null, "내 메일");
        suggestion(leaving, mail.contentRefId());
        suggestion(leaving, teamMessage.contentRefId());
        syncStates.getOrCreate(workspaceId, Provider.GMAIL, "", leaving);

        members.removeMember(workspaceId, owner, leaving);

        assertThat(connections.findAllByWorkspaceIdAndUserId(workspaceId, leaving)).as("연동 기록이 남지 않는다").isEmpty();
        assertThat(targets.findAllByConnectionIdIn(List.of(ownerSlack.getConnectionId())))
                .as("그 사람이 고른 팀 채널은 같은 서비스를 연결한 팀장에게 넘어간다")
                .extracting(IntegrationTarget::getExternalTargetId).containsExactly("C-TEAM");
        assertThat(targets.findAllByConnectionIdIn(List.of(leavingSlack.getConnectionId()))).isEmpty();
        assertThat(count("SELECT count(*) FROM content_ref WHERE workspace_id=? AND owner_user_id=?", leaving))
                .as("DM·메일 같은 개인 자료를 지운다").isZero();
        assertThat(count("SELECT count(*) FROM suggestion WHERE owner_user_id=?", null)).as("후보도 지운다").isZero();
        assertThat(count("SELECT count(*) FROM sync_state WHERE workspace_id=? AND owner_user_id=?", leaving)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_ref WHERE content_ref_id=?", Integer.class,
                teamMessage.contentRefId())).as("팀 채널 대화는 팀 기록이라 남긴다").isEqualTo(1);
        assertThat(dm.contentRefId()).isNotNull();
    }

    @Test
    void 다시_들어와도_예전_연동이_없다() {
        connect(leaving, Provider.SLACK, "C-TEAM");
        members.removeMember(workspaceId, owner, leaving);
        addMember(workspaceId, leaving, "MEMBER");
        assertThat(connections.findAllByWorkspaceIdAndUserId(workspaceId, leaving)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM integration_target t JOIN service_connection c ON c.connection_id = t.connection_id
                 WHERE c.workspace_id = ?""", Integer.class, workspaceId))
                .as("넘겨받을 팀원이 없으면 그 사람이 고른 대상도 지운다").isZero();
    }

    private ServiceConnection connect(Long userId, Provider provider, String... targetIds) {
        ServiceConnection connection = new ServiceConnection(workspaceId, userId, provider, UUID.randomUUID().toString(),
                cipher.encrypt("token-" + userId, "refresh"), "scope", LocalDateTime.now().plusDays(1));
        connection.changeStatus(ConnectionStatus.CONNECTED);
        connection = connections.save(connection);
        for (String target : targetIds) {
            targets.save(new IntegrationTarget(connection.getConnectionId(), target, target));
        }
        return connection;
    }

    private ContentRefDTO content(Long ownerUserId, Provider provider, ContentType type, String channel, String text) {
        return contents.upsert(new ContentRefUpsert(workspaceId, ownerUserId, provider, type, UUID.randomUUID().toString(),
                channel, null, text, text, null, LocalDateTime.now()));
    }

    private void suggestion(Long ownerUserId, Long sourceId) {
        jdbc.update("""
                INSERT INTO suggestion (owner_user_id, type, status, source_content_ref_id, title, created_at, updated_at)
                VALUES (?, 'TASK', 'PENDING', ?, '후보', now(), now())""", ownerUserId, sourceId);
    }

    private int count(String sql, Long userId) {
        return userId == null
                ? jdbc.queryForObject(sql.replace("owner_user_id=?", "owner_user_id=" + leaving), Integer.class)
                : jdbc.queryForObject(sql, Integer.class, workspaceId, userId);
    }
}
