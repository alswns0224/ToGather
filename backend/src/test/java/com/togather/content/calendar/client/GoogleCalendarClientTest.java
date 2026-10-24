package com.togather.content.calendar.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.client.GoogleCalendarClient.EventList;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleCalendarClientTest {

    @Test
    void 기간_일정을_가져오면서_지운_일정_ID를_따로_돌려준다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleCalendarClient client = new GoogleCalendarClient(builder.build());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/calendars/team-cal/events")))
                .andExpect(queryParam("showDeleted", "true"))
                .andRespond(withSuccess("""
                        {"items": [
                          {"id": "e1", "status": "confirmed", "summary": "회의",
                           "start": {"dateTime": "2026-10-20T10:00:00+09:00"},
                           "end": {"dateTime": "2026-10-20T11:00:00+09:00"}},
                          {"id": "e2", "status": "cancelled"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        EventList list = client.listEvents("token", "team-cal", LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 11, 1, 0, 0));

        assertThat(list.events()).extracting(CalendarEvent::id).containsExactly("e1");
        assertThat(list.cancelledIds()).containsExactly("e2");
    }

    @Test
    void 초가_0이어도_초까지_있는_UTC_RFC3339로_보낸다() {
        // 서울 9/27 00:00 = UTC 9/26 15:00. 쿼리에서 공백으로 바뀌는 '+'가 없어야 한다.
        assertThat(GoogleCalendarClient.rfc3339(LocalDateTime.of(2026, 9, 27, 0, 0)))
                .isEqualTo("2026-09-26T15:00:00Z");
    }
}
