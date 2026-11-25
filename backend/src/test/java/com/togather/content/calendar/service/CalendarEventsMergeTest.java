package com.togather.content.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.auth.service.UserService;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.client.GoogleCalendarClient;
import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.dto.CalendarEventResponse;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.TargetAccess;
import com.togather.meeting.repository.MeetingRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 일정 화면: 팀 캘린더가 있어도 내 개인 일정을 함께 보여 준다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalendarEventsMergeTest {

    private static final Long WS = 3L;
    private static final Long USER = 20L;
    private static final String TEAM_CAL = "leader@example.com";
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 1, 0, 0);

    @Mock GoogleCalendarClient calendarClient;
    @Mock IntegrationService integrationService;
    @Mock ContentRefService contentRefService;
    @Mock UserService userService;
    @Mock MeetingRepository meetingRepository;
    @Mock WorkspaceEventService workspaceEventService;
    @InjectMocks CalendarContentService service;

    private final AtomicLong refIds = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        when(contentRefService.upsert(any(ContentRefUpsert.class))).thenAnswer(inv -> {
            ContentRefUpsert c = inv.getArgument(0);
            return new ContentRefDTO(refIds.incrementAndGet(), Provider.GOOGLE_CALENDAR, ContentType.EVENT,
                    c.originId(), c.title(), null, null, null, null, 0, false);
        });
        when(meetingRepository.findAllByCalendarRefIdIn(anyList())).thenReturn(List.of());
    }

    private static CalendarEvent event(String id, String title, int day) {
        LocalDateTime start = LocalDateTime.of(2026, 11, day, 10, 0);
        return new CalendarEvent(id, title, null, start, start.plusHours(1), false, List.of(), null, null, null);
    }

    private void teamCalendar() {
        when(integrationService.findTargetAccess(WS, Provider.GOOGLE_CALENDAR))
                .thenReturn(Optional.of(new TargetAccess(1L, 10L, "team-token", List.of(TEAM_CAL))));
    }

    @Test
    void 팀_캘린더가_있으면_팀_일정과_내_개인_일정을_함께_준다() {
        teamCalendar();
        when(integrationService.findAccessToken(WS, USER, Provider.GOOGLE_CALENDAR)).thenReturn(Optional.of("my-token"));
        when(calendarClient.listEvents("team-token", TEAM_CAL, FROM, TO)).thenReturn(List.of(event("t1", "팀 회의", 5)));
        when(calendarClient.listEvents("my-token", "primary", FROM, TO)).thenReturn(List.of(event("p1", "치과", 3)));

        List<CalendarEventResponse> events = service.getEvents(WS, USER, FROM, TO);

        assertThat(events).extracting(CalendarEventResponse::title).containsExactly("치과", "팀 회의");
        assertThat(events).extracting(CalendarEventResponse::personal).containsExactly(true, false);
    }

    @Test
    void 두_캘린더에_같은_일정이_있으면_팀_일정으로_한_번만_준다() {
        teamCalendar();
        when(integrationService.findAccessToken(WS, USER, Provider.GOOGLE_CALENDAR)).thenReturn(Optional.of("my-token"));
        when(calendarClient.listEvents("team-token", TEAM_CAL, FROM, TO)).thenReturn(List.of(event("t1", "팀 회의", 5)));
        when(calendarClient.listEvents("my-token", "primary", FROM, TO))
                .thenReturn(List.of(event("t1", "팀 회의", 5), event("p1", "치과", 3)));

        List<CalendarEventResponse> events = service.getEvents(WS, USER, FROM, TO);

        assertThat(events).hasSize(2);
        assertThat(events).filteredOn(e -> e.title().equals("팀 회의")).singleElement()
                .extracting(CalendarEventResponse::personal).isEqualTo(false);
    }

    @Test
    void 참여자_캘린더에_넣은_사본은_iCalUID가_같으므로_팀_일정으로_한_번만_준다() {
        teamCalendar();
        LocalDateTime start = LocalDateTime.of(2026, 11, 5, 10, 0);
        when(integrationService.findAccessToken(WS, USER, Provider.GOOGLE_CALENDAR)).thenReturn(Optional.of("my-token"));
        when(calendarClient.listEvents("team-token", TEAM_CAL, FROM, TO)).thenReturn(List.of(
                new CalendarEvent("t1", "팀 회의", null, start, start.plusHours(1), false, List.of(), null, null, null,
                        "uid-1")));
        when(calendarClient.listEvents("my-token", "primary", FROM, TO)).thenReturn(List.of(
                new CalendarEvent("copy-1", "팀 회의", null, start, start.plusHours(1), false, List.of(), null, null,
                        null, "uid-1")));

        List<CalendarEventResponse> events = service.getEvents(WS, USER, FROM, TO);

        assertThat(events).singleElement().extracting(CalendarEventResponse::personal).isEqualTo(false);
    }

    @Test
    void 개인_캘린더를_연동하지_않았으면_팀_일정만_준다() {
        teamCalendar();
        when(integrationService.findAccessToken(WS, USER, Provider.GOOGLE_CALENDAR)).thenReturn(Optional.empty());
        when(calendarClient.listEvents("team-token", TEAM_CAL, FROM, TO)).thenReturn(List.of(event("t1", "팀 회의", 5)));

        List<CalendarEventResponse> events = service.getEvents(WS, USER, FROM, TO);

        assertThat(events).extracting(CalendarEventResponse::title).containsExactly("팀 회의");
        verify(calendarClient, never()).listEvents(eq("my-token"), eq("primary"), any(), any());
    }

    @Test
    void 개인_캘린더를_읽지_못해도_팀_일정은_보여_준다() {
        teamCalendar();
        when(integrationService.findAccessToken(WS, USER, Provider.GOOGLE_CALENDAR))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));
        when(calendarClient.listEvents("team-token", TEAM_CAL, FROM, TO)).thenReturn(List.of(event("t1", "팀 회의", 5)));

        List<CalendarEventResponse> events = service.getEvents(WS, USER, FROM, TO);

        assertThat(events).extracting(CalendarEventResponse::title).containsExactly("팀 회의");
    }

    @Test
    void 팀_캘린더가_없으면_내_개인_일정만_준다() {
        when(integrationService.findTargetAccess(WS, Provider.GOOGLE_CALENDAR)).thenReturn(Optional.empty());
        when(integrationService.getAccessToken(WS, USER, Provider.GOOGLE_CALENDAR)).thenReturn("my-token");
        when(calendarClient.listEvents("my-token", "primary", FROM, TO)).thenReturn(List.of(event("p1", "치과", 3)));

        List<CalendarEventResponse> events = service.getEvents(WS, USER, FROM, TO);

        assertThat(events).extracting(CalendarEventResponse::personal).containsExactly(true);
    }
}
