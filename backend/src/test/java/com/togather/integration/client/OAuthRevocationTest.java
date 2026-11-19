package com.togather.integration.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.togather.auth.client.GoogleOAuthClient;
import com.togather.common.config.TogatherProperties;
import com.togather.common.config.TogatherProperties.Auth;
import com.togather.common.config.TogatherProperties.OAuthClient;
import com.togather.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OAuthRevocationTest {
    private MockRestServiceServer server;
    private RestClient rest;
    private TogatherProperties properties;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rest = builder.build();
        var client = new OAuthClient("test-client", "test-secret");
        properties = new TogatherProperties("http://localhost:5173", "unused", new Auth(false), client, client, client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"ok\":false,\"error\":\"internal_error\"}",
        "{\"ok\":true,\"revoked\":false}"})
    void Slack_HTTP200도_실제_폐기를_확인하지_못하면_실패로_전달한다(String json) {
        server.expect(requestTo("https://slack.com/api/auth.revoke"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new SlackOAuthClient(rest, properties).revoke("test-token"))
            .isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test
    void Slack_폐기_성공을_확인한다() {
        server.expect(requestTo("https://slack.com/api/auth.revoke"))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andRespond(withSuccess("{\"ok\":true,\"revoked\":true}", MediaType.APPLICATION_JSON));
        new SlackOAuthClient(rest, properties).revoke("test-token");
        server.verify();
    }

    @Test
    void Google_폐기_실패를_상위_서비스에_전달한다() {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> new GoogleOAuthClient(rest, properties).revoke("test-token"))
            .isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test
    void Google_토큰은_URL이_아닌_POST_본문에_넣는다() {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string("token=test-token"))
            .andRespond(withSuccess());
        new GoogleOAuthClient(rest, properties).revoke("test-token");
        server.verify();
    }

    @Test
    void Notion_공식_폐기_API를_호출한다() {
        server.expect(requestTo("https://api.notion.com/v1/oauth/revoke"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Notion-Version", "2026-03-11"))
            .andExpect(header("Authorization", "Basic dGVzdC1jbGllbnQ6dGVzdC1zZWNyZXQ="))
            .andExpect(content().json("{\"token\":\"test-token\"}"))
            .andRespond(withSuccess("{\"request_id\":\"test\"}", MediaType.APPLICATION_JSON));
        new NotionOAuthClient(rest, properties).revoke("test-token");
        server.verify();
    }

    @Test
    void Notion_폐기_실패도_상위_서비스에_전달한다() {
        server.expect(requestTo("https://api.notion.com/v1/oauth/revoke"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> new NotionOAuthClient(rest, properties).revoke("test-token"))
            .isInstanceOf(BusinessException.class);
        server.verify();
    }
}
