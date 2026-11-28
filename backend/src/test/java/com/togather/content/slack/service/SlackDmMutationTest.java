package com.togather.content.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.AuthInfo;
import com.togather.content.slack.client.SlackApiClient.DirectMessagePage;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.content.slack.dto.SlackDmResponse.Message;
import com.togather.integration.service.IntegrationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlackDmMutationTest {
    private static final Long WS = 1L;
    private static final Long ME = 10L;
    private static final String MINE = "1700000001.000001";
    private static final String OTHER = "1700000002.000002";
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 28, 9, 0);
    private final SlackApiClient client = mock(SlackApiClient.class);
    private final SlackDirectory directory = mock(SlackDirectory.class);
    private final IntegrationService integrations = mock(IntegrationService.class);
    private final WorkspaceAccessChecker access = mock(WorkspaceAccessChecker.class);
    private final WorkspaceEventService events = mock(WorkspaceEventService.class);
    private final SlackDmService service = new SlackDmService(client, directory, integrations, access, events);

    @BeforeEach
    void setUp() {
        when(integrations.findOwnAccess(WS, ME, Provider.SLACK)).thenReturn(Optional.of(
                new IntegrationService.OwnAccess(100L, "T1:U-ME", "chat:write,im:history", "my-token")));
        when(client.authTest("my-token")).thenReturn(new AuthInfo("T1", "U-ME", "https://example.slack.com"));
        when(client.getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null)))
                .thenReturn(new DirectMessagePage(List.of(message(MINE, "U-ME", "내 메시지"),
                        message(OTHER, "U-OTHER", "상대 메시지")), null));
        when(directory.getUsers(eq(WS), eq("my-token"), anyCollection())).thenReturn(Map.of(
                "U-ME", new SlackUser("U-ME", "나", null), "U-OTHER", new SlackUser("U-OTHER", "상대", null)));
        when(directory.renderMentions(eq(WS), anyString())).thenAnswer(call -> call.getArgument(1));
    }

    @Test
    void 본인_DM을_본인_토큰으로_수정하고_재조회_없이_갱신한다() {
        service.getMessages(WS, ME, "D1");
        // chat.update 응답에 작성자·생성 시각이 빠져도 원래 값을 유지한다.
        when(client.updateMessage("my-token", "D1", MINE, "수정한 내용"))
                .thenReturn(new SlackMessage(MINE, null, null, "수정한 내용", 0, null, null, T, List.of(), null));

        Message updated = service.update(WS, ME, "D1", MINE, "수정한 내용");

        assertThat(updated.text()).isEqualTo("수정한 내용");
        assertThat(updated.userId()).isEqualTo("U-ME");
        assertThat(updated.userName()).isEqualTo("나");
        assertThat(updated.createdAt()).isEqualTo(T);
        assertThat(updated.mine()).isTrue();
        assertThat(updated.edited()).isTrue();
        assertThat(service.getMessages(WS, ME, "D1").getFirst()).isEqualTo(updated);
        verify(client, times(1)).getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null));
        verify(client).updateMessage("my-token", "D1", MINE, "수정한 내용");
        verify(events).publishToUser(WS, ME, SlackDmService.EVENT_PROVIDER, "D1");
        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void 수정용_원문은_표시용_멘션_이름과_분리해_유지한다() {
        String raw = "<@U-OTHER> 자료를 확인해 주세요";
        when(client.getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null)))
                .thenReturn(new DirectMessagePage(List.of(message(MINE, "U-ME", raw)), null));
        when(directory.renderMentions(WS, raw)).thenReturn("@상대 자료를 확인해 주세요");
        Message original = service.getMessages(WS, ME, "D1").getFirst();
        assertThat(original.rawText()).isEqualTo(raw);
        assertThat(original.text()).startsWith("@상대");
        when(client.updateMessage("my-token", "D1", MINE, raw)).thenReturn(message(MINE, "U-ME", raw));

        Message updated = service.update(WS, ME, "D1", MINE, original.rawText());

        verify(client).updateMessage("my-token", "D1", MINE, raw);
        assertThat(updated.rawText()).isEqualTo(raw);
        assertThat(updated.text()).isEqualTo("@상대 자료를 확인해 주세요");
    }

    @Test
    void 본인_DM을_삭제하면_상대_메시지는_남기고_개인_화면에만_알린다() {
        service.getMessages(WS, ME, "D1");

        service.delete(WS, ME, "D1", MINE);

        assertThat(service.getMessages(WS, ME, "D1")).extracting(Message::ts).containsExactly(OTHER);
        verify(client).deleteMessage("my-token", "D1", MINE);
        verify(client, times(1)).getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null));
        verify(events).publishToUser(WS, ME, SlackDmService.EVENT_PROVIDER, "D1");
        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void 상대가_쓴_DM은_수정도_삭제도_Slack_호출_전에_막는다() {
        service.getMessages(WS, ME, "D1");

        assertError(() -> service.update(WS, ME, "D1", OTHER, "변경"), ErrorCode.SLACK_ACTION_FORBIDDEN);
        assertError(() -> service.delete(WS, ME, "D1", OTHER), ErrorCode.SLACK_ACTION_FORBIDDEN);

        verify(client, never()).updateMessage(any(), any(), any(), any());
        verify(client, never()).deleteMessage(any(), any(), any());
        verify(events, never()).publishToUser(any(), any(), any(), any());
    }

    @Test
    void 알_수_없는_메시지나_잘못된_DM요청을_변경하지_않는다() {
        service.getMessages(WS, ME, "D1");
        assertError(() -> service.update(WS, ME, "D1", "1700000099.000001", "변경"), ErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.delete(WS, ME, "D1", "1700000099.000001"), ErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.update(WS, ME, "C-TEAM", MINE, "변경"), ErrorCode.INVALID_INPUT);
        assertError(() -> service.delete(WS, ME, "D1", "invalid"), ErrorCode.INVALID_INPUT);
        assertError(() -> service.update(WS, ME, "D1", MINE, " "), ErrorCode.INVALID_INPUT);
        assertError(() -> service.update(WS, ME, "D1", MINE, "a".repeat(4001)), ErrorCode.INVALID_INPUT);
        verify(client, never()).updateMessage(any(), any(), any(), any());
        verify(client, never()).deleteMessage(any(), any(), any());
    }

    @Test
    void 수정_호출이_실패하면_원래_내용과_캐시를_유지한다() {
        service.getMessages(WS, ME, "D1");
        when(client.updateMessage("my-token", "D1", MINE, "변경"))
                .thenThrow(new SlackApiClient.RateLimitException(30));
        assertError(() -> service.update(WS, ME, "D1", MINE, "변경"), ErrorCode.SLACK_RATE_LIMITED);
        assertThat(service.getMessages(WS, ME, "D1").getFirst().text()).isEqualTo("내 메시지");
        verify(events, never()).publishToUser(any(), any(), any(), any());
    }

    @Test
    void 삭제_권한이나_통신_오류일_때_목록에서_미리_지우지_않는다() {
        service.getMessages(WS, ME, "D1");
        doThrow(new BusinessException(ErrorCode.SLACK_ACTION_FORBIDDEN)).when(client).deleteMessage("my-token", "D1", MINE);
        assertError(() -> service.delete(WS, ME, "D1", MINE), ErrorCode.SLACK_ACTION_FORBIDDEN);
        assertThat(service.getMessages(WS, ME, "D1")).extracting(Message::ts).containsExactly(MINE, OTHER);
        verify(events, never()).publishToUser(any(), any(), any(), any());
    }

    @Test
    void 캐시가_없어도_원본_작성자를_확인한_뒤_수정한다() {
        when(client.updateMessage("my-token", "D1", MINE, "수정"))
                .thenReturn(message(MINE, "U-ME", "수정"));
        assertThat(service.update(WS, ME, "D1", MINE, "수정").text()).isEqualTo("수정");
        verify(client, times(1)).getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null));
    }

    @Test
    void 본인_연결이_없으면_팀원_토큰으로_조회하거나_수정하지_않는다() {
        when(integrations.findOwnAccess(WS, 20L, Provider.SLACK)).thenReturn(Optional.empty());
        when(integrations.findAccessToken(WS, 20L, Provider.SLACK)).thenReturn(Optional.of("teammate-token"));
        assertError(() -> service.getConversations(WS, 20L), ErrorCode.CONNECTION_NOT_FOUND);
        assertError(() -> service.update(WS, 20L, "D1", MINE, "변경"), ErrorCode.CONNECTION_NOT_FOUND);
        assertError(() -> service.delete(WS, 20L, "D1", MINE), ErrorCode.CONNECTION_NOT_FOUND);
        verify(integrations, never()).findAccessToken(any(), any(), any());
        verify(client, never()).updateMessage(any(), any(), any(), any());
        verify(client, never()).deleteMessage(any(), any(), any());
        verify(client, never()).getDirectMessages(any());
    }

    @Test
    void 워크스페이스_접근이_거절되면_Slack을_호출하지_않는다() {
        doThrow(new BusinessException(ErrorCode.SLACK_ACTION_FORBIDDEN)).when(access).checkMember(WS, ME);
        assertError(() -> service.update(WS, ME, "D1", MINE, "변경"), ErrorCode.SLACK_ACTION_FORBIDDEN);
        assertError(() -> service.delete(WS, ME, "D1", MINE), ErrorCode.SLACK_ACTION_FORBIDDEN);
        verify(integrations, never()).findOwnAccess(any(), any(), any());
        verify(client, never()).updateMessage(any(), any(), any(), any());
        verify(client, never()).deleteMessage(any(), any(), any());
    }

    @Test
    void Slack_계정을_바꾸면_이전_계정의_캐시와_작성자_판정을_쓰지_않는다() {
        service.getMessages(WS, ME, "D1");
        when(integrations.findOwnAccess(WS, ME, Provider.SLACK)).thenReturn(Optional.of(
                new IntegrationService.OwnAccess(200L, "T1:U-NEW", "chat:write,im:history", "new-token")));
        when(client.authTest("new-token")).thenReturn(new AuthInfo("T1", "U-NEW", "https://example.slack.com"));
        when(client.getDirectMessageHistory(eq("new-token"), eq("D1"), any(), any(), eq(null)))
                .thenReturn(new DirectMessagePage(List.of(message(MINE, "U-ME", "이전 계정 메시지")), null));
        when(directory.getUsers(eq(WS), eq("new-token"), anyCollection())).thenReturn(Map.of());

        assertError(() -> service.delete(WS, ME, "D1", MINE), ErrorCode.SLACK_ACTION_FORBIDDEN);

        assertThat(service.getMessages(WS, ME, "D1").getFirst().mine()).isFalse();
        verify(client).authTest("new-token");
        verify(client, never()).deleteMessage(any(), any(), any());
    }

    @Test
    void 연결을_해제하면_남아_있는_개인_캐시도_조회하지_못한다() {
        service.getMessages(WS, ME, "D1");
        when(integrations.findOwnAccess(WS, ME, Provider.SLACK)).thenReturn(Optional.empty());
        assertError(() -> service.getMessages(WS, ME, "D1"), ErrorCode.CONNECTION_NOT_FOUND);
        assertError(() -> service.delete(WS, ME, "D1", MINE), ErrorCode.CONNECTION_NOT_FOUND);
        verify(client, never()).deleteMessage(any(), any(), any());
    }

    @Test
    void 수정_웹훅도_대화_기록_재조회_없이_반영한다() {
        service.getMessages(WS, ME, "D1");
        ownWebhook(Map.of("channel", "D1", "subtype", "message_changed", "message",
                Map.of("ts", MINE, "user", "U-ME", "text", "Slack에서 수정")));
        Message edited = service.getMessages(WS, ME, "D1").getFirst();
        assertThat(edited.text()).isEqualTo("Slack에서 수정");
        assertThat(edited.rawText()).isEqualTo("Slack에서 수정");
        assertThat(edited.edited()).isTrue();
        verify(client, times(1)).getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null));
        verify(events).publishToUser(WS, ME, SlackDmService.EVENT_PROVIDER, "D1");
    }

    @Test
    void 삭제_웹훅으로_마지막_메시지가_사라져도_기록을_재조회하지_않는다() {
        when(client.getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null)))
                .thenReturn(new DirectMessagePage(List.of(message(MINE, "U-ME", "삭제 예정")), null));
        service.getMessages(WS, ME, "D1");
        ownWebhook(Map.of("channel", "D1", "subtype", "message_deleted", "deleted_ts", MINE));
        assertThat(service.getMessages(WS, ME, "D1")).isEmpty();
        verify(client, times(1)).getDirectMessageHistory(eq("my-token"), eq("D1"), any(), any(), eq(null));
        verify(events, never()).publish(any(), any(), any());
    }

    private void ownWebhook(Map<String, Object> event) {
        when(integrations.findOwnersByExternalAccount(Provider.SLACK, "T1:U-ME"))
                .thenReturn(List.of(new IntegrationService.ConnectionOwner(WS, ME)));
        service.onDirectMessageEvent(Map.of("team_id", "T1", "authorizations", List.of(Map.of("user_id", "U-ME")), "event", event));
    }

    private static void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }

    private static SlackMessage message(String ts, String author, String text) {
        return new SlackMessage(ts, null, author, text, 0, null, T, null, List.of(), null);
    }
}
