package com.togather.content.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.content.calendar.client.GoogleCalendarClient;
import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.client.GoogleCalendarClient.EventList;
import com.togather.content.calendar.dto.CalendarEventCreateRequest;
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
        when(calendarClient.listEventsWithDeleted(eq(TOKEN), eq("team-cal"), any(), any()))
                .thenReturn(new EventList(List.of(event("e1", "회의"), event("e2", "발표")), List.of()));
        List<CalendarEventResponse> first = calendarContentService.getEvents(workspaceId, userId, FROM, TO);
        Long deletedId = first.getFirst().contentRefId();

        when(calendarClient.listEventsWithDeleted(eq(TOKEN), eq("team-cal"), any(), any()))
                .thenReturn(new EventList(List.of(event("e2", "발표")), List.of("e1", "never-saved")));
        List<CalendarEventResponse> second = calendarContentService.getEvents(workspaceId, userId, FROM, TO);

        assertThat(second).extracting(CalendarEventResponse::title).containsExactly("발표");
        assertThat(contentRefService.getOwner(deletedId).isOriginDeleted()).isTrue();
        assertThat(contentRefService.getOwner(second.getFirst().contentRefId()).isOriginDeleted()).isFalse();
    }

    @Test
    void 개인_일정은_내_기본_캘린더에_참석자_없이_등록한다() {
        when(integrationService.getAccessToken(workspaceId, userId, Provider.GOOGLE_CALENDAR)).thenReturn("my-token");
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
        CalendarEvent created = new CalendarEvent("p1", "치과", "검진", start, start.plusHours(1), false, List.of(),
                "https://calendar/p1", null, start.minusDays(1), null, null);
        when(calendarClient.insertEvent(eq("my-token"), eq("primary"), any(), eq(false))).thenReturn(created);

        CalendarEventResponse response = calendarContentService.createEvent(workspaceId, userId,
                new CalendarEventCreateRequest("치과", start, start.plusHours(1), List.of(userId), "검진", true));

        assertThat(response.personal()).isTrue();
        assertThat(contentRefService.getOwner(response.contentRefId()).ownerUserId()).isEqualTo(userId);
        verify(calendarClient).insertEvent(eq("my-token"), eq("primary"),
                argThat(write -> write.attendeeEmails() == null && write.workspaceId() == null), eq(false));
        verify(calendarClient, never()).insertEvent(eq(TOKEN), eq("team-cal"), any(), anyBoolean());
    }

    /** 이 워크스페이스에서 만든 팀 일정 */
    private CalendarEvent event(String id, String title) {
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
        return new CalendarEvent(id, title, null, start, start.plusHours(1), false, List.of(),
                "https://calendar/" + id, null, start.minusDays(1), null, String.valueOf(workspaceId));
    }

    @Test
    void 표시_없는_예전_일정은_다른_워크스페이스에도_팀_일정으로_저장돼_있으면_팀_일정으로_주지_않는다() {
        Long other = createWorkspace(userId);
        when(integrationService.findTargetAccess(other, Provider.GOOGLE_CALENDAR))
                .thenReturn(Optional.of(new TargetAccess(1L, userId, TOKEN, List.of("team-cal"))));
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
        CalendarEvent legacy = new CalendarEvent("old", "예전 일정", null, start, start.plusHours(1), false, List.of(),
                "https://calendar/old", null, start.minusDays(1));
        // 예전에는 표시 없이도 팀 일정으로 저장됐다.
        for (Long ws : List.of(workspaceId, other)) {
            contentRefService.upsert(new ContentRefService.ContentRefUpsert(ws, null, Provider.GOOGLE_CALENDAR,
                    com.togather.common.enums.ContentType.EVENT, "old", "team-cal", null, "예전 일정", null,
                    "https://calendar/old", start));
        }
        when(calendarClient.listEventsWithDeleted(eq(TOKEN), eq("team-cal"), any(), any()))
                .thenReturn(new EventList(List.of(legacy), List.of()));

        assertThat(calendarContentService.getEvents(workspaceId, userId, FROM, TO))
                .filteredOn(e -> !e.personal()).isEmpty();
    }
}
