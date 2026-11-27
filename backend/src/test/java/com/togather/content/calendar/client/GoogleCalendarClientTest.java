package com.togather.content.calendar.client;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.client.GoogleCalendarClient.EventList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.togather.content.calendar.client.GoogleCalendarClient.EventWrite;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleCalendarClientTest {

    @Test
    void 기존_회의_일정은_필요한_필드만_PATCH하고_Meet_정보를_덮어쓰지_않는다() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/team%40gmail.com/events/event-id?sendUpdates=all"))
            .andExpect(method(HttpMethod.PATCH))
            .andExpect(content().json("""
                {"summary":"바뀐 회의","description":"진행 방식: 온라인",
                 "start":{"dateTime":"2026-11-23T14:00:00","timeZone":"Asia/Seoul"},
                 "end":{"dateTime":"2026-11-23T15:30:00","timeZone":"Asia/Seoul"},
                 "attendees":[{"email":"member@test.example"}]}
                """, JsonCompareMode.STRICT))
            .andRespond(withSuccess("""
                {"id":"event-id","summary":"바뀐 회의","hangoutLink":"https://meet.google.com/test",
                 "start":{"dateTime":"2026-11-23T14:00:00+09:00"},
                 "end":{"dateTime":"2026-11-23T15:30:00+09:00"},"attendees":[{"email":"member@test.example"}]}
                """, MediaType.APPLICATION_JSON));
        var event = new GoogleCalendarClient(builder.build()).patchEvent("test-token", "team@gmail.com", "event-id",
            new EventWrite("바뀐 회의", "진행 방식: 온라인", LocalDateTime.of(2026, 11, 23, 14, 0),
                LocalDateTime.of(2026, 11, 23, 15, 30), List.of("member@test.example")));
        assertThat(event.meetLink()).isEqualTo("https://meet.google.com/test");
        server.verify();
    }

    @Test
    void 일정을_만들_때_워크스페이스_표시를_넣고_읽을_때_꺼낸다() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/calendars/team%40gmail.com/events")))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"extendedProperties":{"private":{"togatherWorkspaceId":"111"}}}
                """, JsonCompareMode.LENIENT))
            .andRespond(withSuccess("""
                {"id":"e1","summary":"리허설",
                 "start":{"dateTime":"2026-11-30T16:00:00+09:00"},"end":{"dateTime":"2026-11-30T17:00:00+09:00"},
                 "extendedProperties":{"private":{"togatherWorkspaceId":"111"}}}
                """, MediaType.APPLICATION_JSON));

        var event = new GoogleCalendarClient(builder.build()).insertEvent("token", "team@gmail.com",
            new EventWrite("리허설", null, LocalDateTime.of(2026, 11, 30, 16, 0), LocalDateTime.of(2026, 11, 30, 17, 0),
                List.of(), 111L), false);

        assertThat(event.workspaceTag()).isEqualTo("111");
        server.verify();
    }

    @Test
    void 초가_0이어도_초까지_있는_UTC_RFC3339로_보낸다() {
        // 서울 9/27 00:00 = UTC 9/26 15:00. 쿼리에서 공백으로 바뀌는 '+'가 없어야 한다.
        assertThat(GoogleCalendarClient.rfc3339(LocalDateTime.of(2026, 9, 27, 0, 0)))
                .isEqualTo("2026-09-26T15:00:00Z");
    }

    @Test
    void 팀_캘린더_ID의_골뱅이는_한_번만_인코딩한다() {
        // 두 번 인코딩하면 %2540이 되어 Google이 캘린더를 찾지 못한다 (404)
        assertThat(GoogleCalendarClient.insertUri("team@gmail.com", true).getRawPath())
                .isEqualTo("/calendar/v3/calendars/team%40gmail.com/events");
    }

    @Test
    void 팀_일정은_참석자_캘린더에도_들어가도록_초대를_보낸다() {
        assertThat(GoogleCalendarClient.insertUri("team@gmail.com", false).getRawQuery())
                .contains("sendUpdates=all");
    }


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

        EventList list = client.listEventsWithDeleted("token", "team-cal", LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 11, 1, 0, 0));

        assertThat(list.events()).extracting(CalendarEvent::id).containsExactly("e1");
        assertThat(list.cancelledIds()).containsExactly("e2");
    }
}
