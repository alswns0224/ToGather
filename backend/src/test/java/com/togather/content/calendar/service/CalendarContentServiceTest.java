package com.togather.content.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.content.calendar.client.GoogleCalendarClient;
import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.client.GoogleCalendarClient.EventList;
import com.togather.content.calendar.dto.CalendarEventResponse;
import com.togather.content.service.ContentRefService;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.TargetAccess;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Google Calendar 일정 조회. Google API와 토큰은 가짜로 바꾼다. */
class CalendarContentServiceTest extends IntegrationTestSupport {

    private static final String TOKEN = "calendar-token";
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 1, 0, 0);

    @MockitoBean
    private GoogleCalendarClient calendarClient;
    @MockitoBean
    private IntegrationService integrationService;

    @Autowired
    private CalendarContentService calendarContentService;
    @Autowired
    private ContentRefService contentRefService;

    private Long userId;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        userId = createUser("철준", "cj@test.com");
        workspaceId = createWorkspace(userId);
        when(integrationService.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR))
                .thenReturn(Optional.of(new TargetAccess(1L, userId, TOKEN, List.of("team-cal"))));
    }

    @Test
    void Google에서_지운_일정은_다음_조회에서_원본_삭제로_표시한다() {
        when(calendarClient.listEvents(eq(TOKEN), eq("team-cal"), any(), any()))
                .thenReturn(new EventList(List.of(event("e1", "회의"), event("e2", "발표")), List.of()));
        List<CalendarEventResponse> first = calendarContentService.getEvents(workspaceId, userId, FROM, TO);
        Long deletedId = first.getFirst().contentRefId();

        when(calendarClient.listEvents(eq(TOKEN), eq("team-cal"), any(), any()))
                .thenReturn(new EventList(List.of(event("e2", "발표")), List.of("e1", "never-saved")));
        List<CalendarEventResponse> second = calendarContentService.getEvents(workspaceId, userId, FROM, TO);

        assertThat(second).extracting(CalendarEventResponse::title).containsExactly("발표");
        assertThat(contentRefService.getOwner(deletedId).isOriginDeleted()).isTrue();
        assertThat(contentRefService.getOwner(second.getFirst().contentRefId()).isOriginDeleted()).isFalse();
    }

    private static CalendarEvent event(String id, String title) {
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
        return new CalendarEvent(id, title, null, start, start.plusHours(1), false, List.of(),
                "https://calendar/" + id, null, start.minusDays(1));
    }
}
