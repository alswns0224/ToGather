package com.togather.content.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.AuthInfo;
import com.togather.content.slack.client.SlackApiClient.HistoryPage;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.content.slack.dto.SlackMessageResponse;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter.SyncContext;
import com.togather.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Slack 실시간 이벤트 → 저장 반영 → 화면 알림. Slack API는 가짜로 바꾼다. */
@TestPropertySource(properties = "togather.slack.signing-secret=test-secret")
class SlackEventServiceTest extends IntegrationTestSupport {

    private static final String TOKEN = "xoxp-test";
    private static final LocalDateTime AT = LocalDateTime.now(SlackApiClient.ZONE).minusHours(1).withNano(100_000);
    private static final String TS = SlackApiClient.toTs(AT);

    @MockitoBean
    private SlackApiClient slackApiClient;
    @MockitoBean
    private IntegrationService integrationService;
    @MockitoBean
    private WorkspaceEventService workspaceEventService;

    @Autowired
    private SlackEventService slackEventService;
    @Autowired
    private SlackSyncAdapter slackSyncAdapter;
    @Autowired
    private SlackContentService slackContentService;

    private Long userId;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        userId = createUser("민준", "mj@test.com");
        workspaceId = createWorkspace(userId);
        when(integrationService.getConnectionTargets(workspaceId, Provider.SLACK)).thenReturn(List.of("C1"));
        when(integrationService.findWorkspacesByTarget(Provider.SLACK, "C1")).thenReturn(List.of(workspaceId));
        when(integrationService.getAccessToken(eq(workspaceId), any(), eq(Provider.SLACK))).thenReturn(TOKEN);
        when(integrationService.findAccessToken(eq(workspaceId), any(), eq(Provider.SLACK))).thenReturn(Optional.of(TOKEN));
        // 이벤트 처리에는 요청한 사용자가 없으므로, 채널을 연동 대상으로 고른 연결의 토큰을 쓴다.
        when(integrationService.findTargetAccess(workspaceId, Provider.SLACK))
                .thenReturn(Optional.of(new IntegrationService.TargetAccess(1L, userId, TOKEN, List.of("C1"))));
        when(slackApiClient.getChannelName(TOKEN, "C1")).thenReturn("general");
        when(slackApiClient.authTest(TOKEN)).thenReturn(new AuthInfo("T1", "U1", "https://team.slack.com/"));
        when(slackApiClient.getUser(TOKEN, "U1")).thenReturn(new SlackUser("U1", "민준", "mj@test.com"));
        when(slackApiClient.getHistory(eq(TOKEN), eq("C1"), any(), anyInt()))
                .thenReturn(new HistoryPage(List.of(message(TS, "안녕하세요")), false));
    }

    @Test
    void Slack이_보낸_서명만_받는다() {
        String body = "{\"type\":\"event_callback\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        assertThat(slackEventService.verify(ts, sign(ts, body), body)).isTrue();
        assertThat(slackEventService.verify(ts, sign(ts, body), body + " ")).isFalse(); // 본문이 바뀜
        assertThat(slackEventService.verify(ts, "v0=00", body)).isFalse();
        String old = String.valueOf(Instant.now().getEpochSecond() - 600);
        assertThat(slackEventService.verify(old, sign(old, body), body)).isFalse(); // 10분 전 요청
    }

    @Test
    void 새_메시지_이벤트가_오면_그_채널을_바로_동기화하고_화면에_알린다() {
        slackEventService.handleMessage(Map.of("type", "message", "channel", "C1", "ts", TS, "user", "U1", "text", "안녕하세요"));

        assertThat(texts()).containsExactly("안녕하세요");
        verify(workspaceEventService).publish(workspaceId, "SLACK", "C1");
    }

    @Test
    void 수정_이벤트가_오면_본문을_바꾼다() {
        slackSyncAdapter.sync(new SyncContext(workspaceId, "C1", null, null, userId));

        slackEventService.handleMessage(Map.of("type", "message", "subtype", "message_changed", "channel", "C1",
                "message", Map.of("ts", TS, "text", "안녕하세요 (수정)", "edited", Map.of("ts", SlackApiClient.toTs(AT.plusMinutes(5))))));

        assertThat(texts()).containsExactly("안녕하세요 (수정)");
        verify(workspaceEventService).publish(workspaceId, "SLACK", "C1");
    }

    @Test
    void 삭제_이벤트가_오면_채널_화면에서_뺀다() {
        slackSyncAdapter.sync(new SyncContext(workspaceId, "C1", null, null, userId));

        slackEventService.handleMessage(Map.of("type", "message", "subtype", "message_deleted", "channel", "C1",
                "deleted_ts", TS));

        assertThat(texts()).isEmpty();
    }

    @Test
    void 연동하지_않은_채널의_이벤트는_무시한다() {
        when(integrationService.findWorkspacesByTarget(Provider.SLACK, "C9")).thenReturn(List.of());

        slackEventService.handleMessage(Map.of("type", "message", "channel", "C9", "ts", TS, "text", "다른 채널"));

        assertThat(texts()).isEmpty();
    }

    private List<String> texts() {
        return slackContentService.getChannelMessages(workspaceId, userId, "C1", PageRequest.of(0, 20)).items()
                .stream().map(SlackMessageResponse::text).toList();
    }

    private static SlackMessage message(String ts, String text) {
        return new SlackMessage(ts, null, "U1", text, 0, null, SlackApiClient.toLocal(ts), null, List.of(), null);
    }

    private static String sign(String ts, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v0=" + HexFormat.of().formatHex(mac.doFinal(("v0:" + ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
