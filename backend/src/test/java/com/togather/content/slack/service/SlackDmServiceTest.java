package com.togather.content.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.AuthInfo;
import com.togather.content.slack.client.SlackApiClient.DirectMessage;
import com.togather.content.slack.client.SlackApiClient.DirectMessagePage;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.content.slack.dto.SlackDmResponse.Conversation;
import com.togather.content.slack.dto.SlackDmResponse.Message;
import com.togather.integration.service.IntegrationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 내 Slack DM 보기·답장: 늘 내 Slack 연결 토큰으로 읽고 보낸다. */
class SlackDmServiceTest {

    private static final Long WS = 1L;
    private static final Long ME = 10L;
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 27, 10, 0);

    private final SlackApiClient client = mock(SlackApiClient.class);
    private final SlackDirectory directory = mock(SlackDirectory.class);
    private final IntegrationService integrations = mock(IntegrationService.class);
    private final com.togather.common.event.WorkspaceEventService events =
            mock(com.togather.common.event.WorkspaceEventService.class);
    private final SlackDmService service = new SlackDmService(client, directory, integrations,
            mock(WorkspaceAccessChecker.class), events);

    @BeforeEach
    void setUp() {
        when(integrations.findOwnAccess(WS, ME, Provider.SLACK)).thenReturn(Optional.of(
                new IntegrationService.OwnAccess(100L, "T1:U-ME", "im:read,im:history,chat:write", "my-token")));
        when(client.authTest("my-token")).thenReturn(new AuthInfo("T1", "U-ME", "https://x.slack.com"));
        when(directory.getUsers(eq(WS), eq("my-token"), anyCollection())).thenReturn(Map.of(
                "U-ME", new SlackUser("U-ME", "백민준", null), "U-LEE", new SlackUser("U-LEE", "이찬서", null)));
        when(directory.renderMentions(eq(WS), anyString())).thenAnswer(call -> call.getArgument(1));
    }

    @Test
    void 내_DM_목록을_상대_이름으로_주고_Slackbot은_뺀다() {
        when(client.getDirectMessages("my-token")).thenReturn(List.of(
                new DirectMessage("D1", "U-LEE"), new DirectMessage("D2", "USLACKBOT")));
        when(client.getReadState("my-token", "D1")).thenReturn(new SlackApiClient.ReadState(null, 0));

        List<Conversation> dms = service.getConversations(WS, ME);

        assertThat(dms).extracting(Conversation::channelId, Conversation::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("D1", "이찬서"));
    }

    @Test
    void 안_읽은_수는_대화_기록을_읽지_않고_Slack이_표시하는_수를_준다() {
        when(client.getDirectMessages("my-token")).thenReturn(List.of(new DirectMessage("D1", "U-LEE")));
        when(client.getReadState("my-token", "D1")).thenReturn(new SlackApiClient.ReadState("1700000001.000000", 2));

        Conversation dm = service.getConversations(WS, ME).getFirst();

        assertThat(dm.unreadCount()).isEqualTo(2);
        assertThat(dm.lastRead()).isEqualTo("1700000001.000000");
        verify(client, never()).getDirectMessageHistory(any(), any(), any(), any(), any());
    }

    @Test
    void 안_읽은_수를_확인하지_못해도_목록은_준다() {
        when(client.getDirectMessages("my-token")).thenReturn(List.of(new DirectMessage("D1", "U-LEE")));
        when(client.getReadState("my-token", "D1")).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));

        assertThat(service.getConversations(WS, ME)).singleElement()
                .extracting(Conversation::unreadCount).isEqualTo(0);
    }

    @Test
    void DM_메시지는_시간순으로_주고_내가_보낸_것을_표시한다() {
        when(client.getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null)))
                .thenReturn(new DirectMessagePage(List.of(
                        message("1700000002.000", "U-ME", "넵 확인했어요"),
                        message("1700000001.000", "U-LEE", "자료 봤어요?")), null));

        List<Message> messages = service.getMessages(WS, ME, "D1");

        assertThat(messages).extracting(Message::text, Message::userName, Message::mine).containsExactly(
                org.assertj.core.groups.Tuple.tuple("자료 봤어요?", "이찬서", false),
                org.assertj.core.groups.Tuple.tuple("넵 확인했어요", "백민준", true));
    }

    @Test
    void 잠깐_사이에_다시_부르면_Slack을_다시_부르지_않고_기억한_결과를_준다() {
        when(client.getDirectMessages("my-token")).thenReturn(List.of(new DirectMessage("D1", "U-LEE")));
        when(client.getReadState("my-token", "D1")).thenReturn(new SlackApiClient.ReadState(null, 0));
        when(client.getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null)))
                .thenReturn(new DirectMessagePage(List.of(message("1700000001.000", "U-LEE", "안녕")), null));

        service.getConversations(WS, ME);
        service.getConversations(WS, ME);
        service.getMessages(WS, ME, "D1");
        service.getMessages(WS, ME, "D1");

        verify(client, org.mockito.Mockito.times(1)).getDirectMessages("my-token");
        verify(client, org.mockito.Mockito.times(1)).getReadState("my-token", "D1");
        // 대화 기록은 대화를 처음 열 때 1번만
        verify(client, org.mockito.Mockito.times(1)).getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null));
        verify(client, org.mockito.Mockito.times(1)).authTest("my-token");
    }

    @Test
    void Slack_한도에_걸리면_직전에_받은_메시지를_준다() throws Exception {
        when(client.getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null)))
                .thenReturn(new DirectMessagePage(List.of(message("1700000001.000", "U-LEE", "안녕")), null))
                .thenThrow(new SlackApiClient.RateLimitException(30));
        service.getMessages(WS, ME, "D1");
        expireCaches();

        assertThat(service.getMessages(WS, ME, "D1")).extracting(Message::text).containsExactly("안녕");
    }

    /** 기억한 결과가 오래된 것처럼 만든다. */
    @SuppressWarnings("unchecked")
    private void expireCaches() throws Exception {
        for (String name : List.of("lists", "histories")) {
            var field = SlackDmService.class.getDeclaredField(name);
            field.setAccessible(true);
            var map = (Map<String, Object>) field.get(service);
            for (var entry : map.entrySet()) {
                var value = entry.getValue();
                var ctor = value.getClass().getDeclaredConstructors()[0];
                ctor.setAccessible(true);
                var inner = value.getClass().getDeclaredMethod("value");
                inner.setAccessible(true);
                entry.setValue(ctor.newInstance(inner.invoke(value), java.time.Instant.EPOCH));
            }
        }
    }

    @Test
    void DM_웹훅이_오면_Slack을_다시_부르지_않고_새_메시지와_안_읽은_수를_고치고_본인에게만_알린다() {
        when(client.getDirectMessages("my-token")).thenReturn(List.of(new DirectMessage("D1", "U-LEE")));
        when(client.getReadState("my-token", "D1")).thenReturn(new SlackApiClient.ReadState(null, 1));
        when(client.getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null)))
                .thenReturn(new DirectMessagePage(List.of(message("1700000001.000", "U-LEE", "안녕")), null));
        service.getConversations(WS, ME);
        service.getMessages(WS, ME, "D1");
        when(integrations.findOwnersByExternalAccount(Provider.SLACK, "T1:U-ME"))
                .thenReturn(List.of(new com.togather.integration.service.IntegrationService.ConnectionOwner(WS, ME)));

        service.onDirectMessageEvent(Map.of("team_id", "T1",
                "authorizations", List.of(Map.of("user_id", "U-ME")),
                "event", Map.of("type", "message", "channel_type", "im", "channel", "D1", "user", "U-LEE",
                        "text", "새 메시지", "ts", "1700000009.000100")));

        assertThat(service.getMessages(WS, ME, "D1")).extracting(Message::text).containsExactly("안녕", "새 메시지");
        assertThat(service.getConversations(WS, ME).getFirst().unreadCount()).isEqualTo(2);
        // 처음 한 번씩만 Slack을 불렀다.
        verify(client, org.mockito.Mockito.times(1)).getReadState("my-token", "D1");
        verify(client, org.mockito.Mockito.times(1)).getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null));
        verify(events).publishToUser(WS, ME, SlackDmService.EVENT_PROVIDER, "D1");
        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void DM이_아닌_대화의_이벤트는_무시한다() {
        service.onDirectMessageEvent(Map.of("team_id", "T1",
                "event", Map.of("type", "message", "channel", "C-TEAM", "user", "U-LEE", "ts", "1.0")));

        verify(events, never()).publishToUser(any(), any(), any(), any());
    }

    @Test
    void 답장은_내_토큰으로_보낸다() {
        when(client.postMessage("my-token", "D1", null, "좋아요"))
                .thenReturn(message("1700000003.000", "U-ME", "좋아요"));

        Message sent = service.send(WS, ME, "D1", "좋아요");

        assertThat(sent.mine()).isTrue();
        verify(client).postMessage("my-token", "D1", null, "좋아요");
    }

    @Test
    void 내가_Slack을_연결하지_않았거나_DM이_아닌_대화면_막는다() {
        when(integrations.findOwnAccess(WS, 20L, Provider.SLACK)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConversations(WS, 20L))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.CONNECTION_NOT_FOUND);
        assertThatThrownBy(() -> service.send(WS, ME, "C-CHANNEL", "안녕"))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(client, never()).postMessage(any(), any(), any(), any());
    }

    private static SlackMessage message(String ts, String user, String text) {
        return new SlackMessage(ts, null, user, text, 0, null, T, null, List.of(), null);
    }
}
