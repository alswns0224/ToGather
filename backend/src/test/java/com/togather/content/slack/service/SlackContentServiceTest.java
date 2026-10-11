package com.togather.content.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.AuthInfo;
import com.togather.content.slack.client.SlackApiClient.HistoryPage;
import com.togather.content.slack.client.SlackApiClient.SlackFile;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.content.slack.dto.SlackMessageResponse;
import com.togather.content.slack.dto.ThreadDetailResponse;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter.SyncContext;
import com.togather.sync.service.SyncAdapter.SyncResult;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Slack 동기화 → 조회 흐름. Slack API와 토큰은 가짜로 바꾼다. */
class SlackContentServiceTest extends IntegrationTestSupport {

    private static final String TOKEN = "xoxp-test";
    /** AI 업무 보조는 최근 14일만 보므로 하루 전 메시지로 만든다. */
    private static final LocalDateTime ROOT_AT = LocalDateTime.now(SlackApiClient.ZONE).minusDays(1).withNano(100_000);
    private static final String ROOT_TS = SlackApiClient.toTs(ROOT_AT);
    private static final String REPLY_TS = SlackApiClient.toTs(ROOT_AT.plusMinutes(1));

    @MockitoBean
    private SlackApiClient slackApiClient;
    @MockitoBean
    private IntegrationService integrationService;

    @Autowired
    private SlackSyncAdapter slackSyncAdapter;
    @Autowired
    private SlackContentService slackContentService;

    private Long userId;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        userId = createUser("철준", "cj@test.com");
        workspaceId = createWorkspace(userId);
        when(integrationService.getConnectionTargets(workspaceId, Provider.SLACK)).thenReturn(List.of("C1"));
        when(integrationService.getAccessToken(eq(workspaceId), any(), eq(Provider.SLACK))).thenReturn(TOKEN);
        when(integrationService.findAccessToken(eq(workspaceId), any(), eq(Provider.SLACK)))
                .thenReturn(Optional.of(TOKEN));
        when(slackApiClient.getChannelName(TOKEN, "C1")).thenReturn("general");
        when(slackApiClient.authTest(TOKEN)).thenReturn(new AuthInfo("T1", "U1", "https://team.slack.com/"));
        when(slackApiClient.getUser(TOKEN, "U1")).thenReturn(new SlackUser("U1", "철준", "cj@test.com"));
        when(slackApiClient.getUser(TOKEN, "U2")).thenReturn(new SlackUser("U2", "민준", "mj@test.com"));
        when(slackApiClient.findUserByEmail(TOKEN, "cj@test.com")).thenReturn(new SlackUser("U1", "철준", "cj@test.com"));

        SlackMessage root = message(ROOT_TS, ROOT_TS, "U1", "회의는 <@U2> 목요일로 할까요?", 1, REPLY_TS);
        when(slackApiClient.getHistory(eq(TOKEN), eq("C1"), any(), anyInt()))
                .thenReturn(new HistoryPage(List.of(root,
                        message(SlackApiClient.toTs(ROOT_AT.minusMinutes(1)), null, "U2", "입장", 0, null, "channel_join")), false));
        when(slackApiClient.getReplies(eq(TOKEN), eq("C1"), eq(ROOT_TS), any()))
                .thenReturn(List.of(root, message(REPLY_TS, ROOT_TS, "U2", "좋아요", 0, null)));
    }

    @Test
    void 채널을_동기화하면_root와_답글을_저장하고_시스템_메시지는_건너뛴다() {
        SyncResult result = slackSyncAdapter.sync(new SyncContext(workspaceId, "C1", null, null, userId));

        assertThat(result.savedCount()).isEqualTo(2);
        var page = slackContentService.getChannelMessages(workspaceId, userId, "C1", PageRequest.of(0, 20));
        assertThat(page.items()).hasSize(1);
        SlackMessageResponse root = page.items().getFirst();
        assertThat(root.authorName()).isEqualTo("철준");
        assertThat(root.text()).isEqualTo("회의는 @민준 목요일로 할까요?");
        assertThat(root.replyCount()).isEqualTo(1);
        assertThat(root.mine()).isTrue();
        assertThat(root.attachments()).extracting(SlackMessageFormat.Attachment::name).containsExactly("회의록.docx");
        assertThat(root.deepLink()).isEqualTo("https://team.slack.com/archives/C1/p" + ROOT_TS.replace(".", ""));
    }

    @Test
    void 스레드_상세는_Slack이_실패해도_저장된_답글을_보여준다() {
        slackSyncAdapter.sync(new SyncContext(workspaceId, "C1", null, null, userId));
        when(slackApiClient.getReplies(eq(TOKEN), eq("C1"), eq(ROOT_TS), isNull()))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));

        ThreadDetailResponse thread = slackContentService.getThreadDetail(workspaceId, userId, "C1", ROOT_TS);

        assertThat(thread.refreshFailed()).isTrue();
        assertThat(thread.replies()).extracting(SlackMessageResponse::text).containsExactly("좋아요");
        assertThat(thread.replies().getFirst().mine()).isFalse();
    }

    @Test
    void 사용자가_쓰거나_멘션된_메시지를_찾는다() {
        slackSyncAdapter.sync(new SyncContext(workspaceId, "C1", null, null, userId));
        Long otherUser = createUser("민준", "mj@test.com");
        addMember(workspaceId, otherUser, "MEMBER");
        when(slackApiClient.findUserByEmail(TOKEN, "mj@test.com")).thenReturn(new SlackUser("U2", "민준", "mj@test.com"));

        List<ContentRefDTO> mine = slackContentService.getContentsByUser(workspaceId, userId);
        List<ContentRefDTO> mentioned = slackContentService.getContentsByUser(workspaceId, otherUser);

        assertThat(mine).hasSize(1);
        assertThat(mentioned).hasSize(2); // 본인 답글 + 멘션된 root
    }

    @Test
    void 연동하지_않은_채널은_조회할_수_없다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        slackContentService.getChannelMessages(workspaceId, userId, "C9", PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SLACK_CHANNEL_NOT_LINKED));
    }

    @Test
    void 증분_동기화는_마지막_동기화_전후만_다시_본다() {
        LocalDateTime since = LocalDateTime.of(2026, 10, 7, 9, 0);
        when(slackApiClient.getHistory(eq(TOKEN), eq("C1"), eq(since.minus(SlackSyncAdapter.LOOKBACK)), anyInt()))
                .thenReturn(new HistoryPage(List.of(), false));

        SyncResult result = slackSyncAdapter.sync(new SyncContext(workspaceId, "C1", null, since, userId));

        assertThat(result.savedCount()).isZero();
        assertThat(result.syncedUntil()).isAfter(since);
    }

    private static SlackMessage message(String ts, String threadTs, String user, String text, int replies,
                                        String latestReply) {
        return message(ts, threadTs, user, text, replies, latestReply, null);
    }

    private static SlackMessage message(String ts, String threadTs, String user, String text, int replies,
                                        String latestReply, String subtype) {
        List<SlackFile> files = ROOT_TS.equals(ts)
                ? List.of(new SlackFile("회의록.docx", "https://files/1", "application/msword"))
                : List.of();
        return new SlackMessage(ts, threadTs, user, text, replies, latestReply, SlackApiClient.toLocal(ts), null,
                files, subtype);
    }
}
