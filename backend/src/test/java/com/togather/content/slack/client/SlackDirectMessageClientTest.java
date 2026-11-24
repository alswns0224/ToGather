package com.togather.content.slack.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SlackDirectMessageClientTest {
    private SlackApiClient client;
    private MockRestServiceServer server;
    private final LocalDateTime oldest = LocalDateTime.of(2026, 11, 1, 12, 0);
    private final LocalDateTime latest = oldest.plusDays(14);

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SlackApiClient(builder.build());
    }

    @Test
    void DM_목록은_본인_사용자토큰으로_읽고_중복과_다른대화유형을_제외한다() {
        server.expect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer personal-token"))
                .andExpect(queryParam("types", "im"))
                .andRespond(withSuccess("""
                    {"ok":true,"channels":[{"id":"D1","is_im":true,"user":"U2"},
                      {"id":"C1","is_im":false},{"id":"G1","is_mpim":true}],
                      "response_metadata":{"next_cursor":"page-two"}}
                    """, MediaType.APPLICATION_JSON));
        server.expect(queryParam("cursor", "page-two")).andRespond(withSuccess("""
                    {"ok":true,"channels":[{"id":"D1","is_im":true,"user":"U2"},{"id":"D2","is_im":true,"user":"U3"}],
                     "response_metadata":{"next_cursor":""}}
                    """, MediaType.APPLICATION_JSON));
        assertThat(client.getDirectMessages("personal-token")).extracting(SlackApiClient.DirectMessage::id).containsExactly("D1", "D2");
        server.verify();
    }

    @Test
    void 수집_구간과_긴_커서는_변형없이_요청하고_이어받을_위치를_반환한다() {
        String cursor = "opaque+/=" + "x".repeat(300);
        server.expect(queryParam("channel", "D1")).andExpect(request -> {
                    String encoded = java.util.Arrays.stream(request.getURI().getRawQuery().split("&"))
                            .filter(part -> part.startsWith("cursor=")).findFirst().orElseThrow().substring(7);
                    assertThat(java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(cursor);
                    assertThat(encoded).contains("%2B", "%2F", "%3D");
                })
                .andExpect(queryParam("oldest", SlackApiClient.toTs(oldest)))
                .andExpect(queryParam("latest", SlackApiClient.toTs(latest))).andExpect(queryParam("inclusive", "true"))
                .andRespond(withSuccess("""
                    {"ok":true,"messages":[{"ts":"1793502000.000000","user":"U2","text":"준비 요청"}],
                     "has_more":true,"response_metadata":{"next_cursor":"next-page"}}
                    """, MediaType.APPLICATION_JSON));
        var page = client.getDirectMessageHistory("token", "D1", oldest, latest, cursor);
        assertThat(page.messages()).hasSize(1);
        assertThat(page.nextCursor()).isEqualTo("next-page");
        server.verify();
    }

    @Test
    void 잘린_응답에_커서가_없으면_수집완료로_취급하지_않는다() {
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"ok\":true,\"messages\":[],\"has_more\":true}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getDirectMessageHistory("token", "D1", oldest, latest, null)).isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test
    void 스레드_답글은_replies에_원본시각과_구간을_전달한다() {
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("/conversations.replies"))
                .andExpect(queryParam("ts", "1793502000.000000"))
                .andRespond(withSuccess("{\"ok\":true,\"messages\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.getDirectMessageReplies("token", "D1", "1793502000.000000", oldest, latest, null).nextCursor()).isNull();
        server.verify();
    }

    @Test
    void DM_읽기권한이_없으면_재연결_안내용_코드를_반환한다() {
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"ok\":false,\"error\":\"missing_scope\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.getDirectMessages("token")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SLACK_SCOPE_REQUIRED));
        server.verify();
    }

    @Test
    void 요청한도_응답의_대기시간을_보존한다() {
        server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "600"));
        assertThatThrownBy(() -> client.getDirectMessages("token")).isInstanceOfSatisfying(SlackApiClient.RateLimitException.class,
                error -> assertThat(error.retryAfterSeconds()).isEqualTo(600));
        server.verify();
    }

    @Test
    void 일반채널이나_잘못된ID로_DM_읽기를_호출하지_않는다() {
        assertThatThrownBy(() -> client.getDirectMessageHistory("token", "C1", oldest, latest, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.getDirectMessageHistory("token", "D1/path", oldest, latest, null)).isInstanceOf(BusinessException.class);
        server.verify();
    }
}
