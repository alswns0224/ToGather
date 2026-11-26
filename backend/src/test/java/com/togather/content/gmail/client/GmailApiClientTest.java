package com.togather.content.gmail.client;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import com.togather.content.gmail.client.GmailApiClient.HistoryResult;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GmailApiClientTest {
    private MockRestServiceServer server;
    private GmailApiClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GmailApiClient(builder.build());
    }

    @Test
    void messageNotFoundIsDistinguishedFromAnApiFailure() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/gone?format=full"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getMessage("test-token", "gone"))
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        server.verify();
    }

    @Test
    void expiredHistoryStillRequiresHistoryRecovery() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/history?startHistoryId=1000&maxResults=100"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.listHistory("test-token", "1000", 5))
            .isInstanceOf(GmailApiClient.HistoryExpiredException.class);
        server.verify();
    }

    @Test
    void unauthorizedMessageStillRequiresReauthentication() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/mail?format=full"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getMessage("test-token", "mail"))
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode())
                    .isEqualTo(ErrorCode.REAUTH_REQUIRED));
        server.verify();
    }

    @Test
    void serverFailureStillFailsTheSync() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/mail?format=full"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.getMessage("test-token", "mail"))
            .isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode())
                    .isEqualTo(ErrorCode.EXTERNAL_API_ERROR));
        server.verify();
    }


    @Test
    void 영구_삭제와_휴지통_이동을_삭제로_보고_다시_조회하지_않는다() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/history?startHistoryId=100")))
                .andRespond(withSuccess("""
                        {"historyId": "120", "history": [
                          {"messages": [{"id": "m1"}], "messagesDeleted": [{"message": {"id": "m1"}}]},
                          {"messages": [{"id": "m2"}],
                           "labelsAdded": [{"message": {"id": "m2"}, "labelIds": ["TRASH"]}]},
                          {"messages": [{"id": "m3"}],
                           "labelsAdded": [{"message": {"id": "m3"}, "labelIds": ["STARRED"]}]}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        HistoryResult result = client.listHistory("token", "100", 5);

        assertThat(result.deletedMessageIds()).containsExactly("m1", "m2");
        assertThat(result.changedMessageIds()).containsExactly("m3");
        assertThat(result.latestHistoryId()).isEqualTo("120");
    }
}
