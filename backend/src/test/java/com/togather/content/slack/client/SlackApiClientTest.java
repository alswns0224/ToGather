package com.togather.content.slack.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.slack.client.SlackApiClient.HistoryPage;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SlackApiClientTest {

    private MockRestServiceServer server;
    private SlackApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SlackApiClient(builder.build());
    }

    @Test
    void ts를_서울_시간으로_바꾸고_되돌린다() {
        // 1759800000 = 2025-10-07T01:20:00Z = 서울 10:20
        LocalDateTime time = SlackApiClient.toLocal("1759800000.000200");
        assertThat(time).isEqualTo(LocalDateTime.of(2025, 10, 7, 10, 20, 0, 200_000));
        assertThat(SlackApiClient.toTs(time)).isEqualTo("1759800000.000200");
    }

    @Test
    void 채널_메시지를_페이지를_넘겨가며_읽는다() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://slack.com/api/conversations.history")))
                .andExpect(header("Authorization", "Bearer xoxp-test"))
                .andExpect(queryParam("channel", "C1"))
                .andRespond(withSuccess("""
                        {"ok": true, "messages": [
                          {"ts": "1759800000.000200", "user": "U1", "text": "안녕", "reply_count": 2,
                           "thread_ts": "1759800000.000200", "latest_reply": "1759800100.000100",
                           "files": [{"name": "기획서.pdf", "permalink": "https://files/1"}]}
                        ], "response_metadata": {"next_cursor": "abc"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("cursor=abc")))
                .andRespond(withSuccess("""
                        {"ok": true, "messages": [{"ts": "1759700000.000100", "user": "U2", "text": "이전",
                          "edited": {"ts": "1759700500.000000"}}],
                         "response_metadata": {"next_cursor": ""}}
                        """, MediaType.APPLICATION_JSON));

        HistoryPage page = client.getHistory("xoxp-test", "C1", null, 5);

        assertThat(page.truncated()).isFalse();
        assertThat(page.messages()).hasSize(2);
        SlackMessage first = page.messages().getFirst();
        assertThat(first.hasReplies()).isTrue();
        assertThat(first.isReply()).isFalse();
        assertThat(first.files()).extracting(SlackApiClient.SlackFile::name).containsExactly("기획서.pdf");
        assertThat(page.messages().get(1).editedAt()).isNotNull();
    }

    @Test
    void 토큰이_취소되면_재인증_필요() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://slack.com/api/conversations.history")))
                .andRespond(withSuccess("{\"ok\": false, \"error\": \"token_revoked\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getHistory("xoxp-test", "C1", null, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REAUTH_REQUIRED));
    }

    @Test
    void 남의_메시지를_수정하면_권한_없음() {
        server.expect(requestTo("https://slack.com/api/chat.update"))
                .andRespond(withSuccess("{\"ok\": false, \"error\": \"cant_update_message\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.updateMessage("xoxp-test", "C1", "1.0", "수정"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SLACK_ACTION_FORBIDDEN));
    }

    @Test
    void 답글을_작성한다() {
        server.expect(requestTo("https://slack.com/api/chat.postMessage"))
                .andExpect(jsonPath("$.thread_ts").value("1759800000.000200"))
                .andExpect(jsonPath("$.text").value("좋아요"))
                .andRespond(withSuccess("""
                        {"ok": true, "ts": "1759800200.000300",
                         "message": {"user": "U1", "text": "좋아요", "thread_ts": "1759800000.000200"}}
                        """, MediaType.APPLICATION_JSON));

        SlackMessage reply = client.postMessage("xoxp-test", "C1", "1759800000.000200", "좋아요");

        assertThat(reply.ts()).isEqualTo("1759800200.000300");
        assertThat(reply.isReply()).isTrue();
    }

    @Test
    void 이미_지운_메시지는_삭제_성공으로_본다() {
        server.expect(requestTo("https://slack.com/api/chat.delete"))
                .andRespond(withSuccess("{\"ok\": false, \"error\": \"message_not_found\"}",
                        MediaType.APPLICATION_JSON));

        client.deleteMessage("xoxp-test", "C1", "1.0");
        server.verify();
    }
}
