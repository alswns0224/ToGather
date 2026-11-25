package com.togather.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.common.enums.SlotPreference;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.client.GoogleCalendarClient;
import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.client.GoogleCalendarClient.EventWrite;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.TargetAccess;
import com.togather.meeting.dto.AvailabilitySaveRequest;
import com.togather.meeting.dto.AvailabilitySaveRequest.SlotInput;
import com.togather.meeting.dto.ConflictResponse;
import com.togather.meeting.dto.MeetingCreateRequest;
import com.togather.meeting.dto.MeetingDetailResponse;
import com.togather.meeting.dto.MeetingUpdateRequest;
import com.togather.meeting.dto.TimeCandidateResponse;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 회의를 확정하면 참여자 캘린더에도 들어가고, 확정 직전에 참여자들의 지금 일정을 다시 확인한다. */
class MeetingParticipantCalendarTest extends IntegrationTestSupport {

    private static final String LEADER_TOKEN = "leader-token";
    private static final String MEMBER_TOKEN = "member-token";
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 12, 2, 9, 0);

    @Autowired private MeetingService meetings;
    @MockitoBean private IntegrationService integrations;
    @MockitoBean private GoogleCalendarClient calendar;

    private Long leaderId;
    private Long memberId;
    private Long workspaceId;
    private String teamCalendar;

    @BeforeEach
    void setUp() {
        teamCalendar = UUID.randomUUID() + "@test.example";
        leaderId = createUser("팀장", teamCalendar); // 팀 캘린더 = 팀장 기본 캘린더
        memberId = createUser("팀원", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(leaderId);
        addMember(workspaceId, memberId, "MEMBER");

        var team = new TargetAccess(1L, leaderId, LEADER_TOKEN, List.of(teamCalendar));
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR)).thenReturn(Optional.of(team));
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR, teamCalendar))
            .thenReturn(Optional.of(team));
        when(integrations.getAccessToken(workspaceId, leaderId, Provider.GOOGLE_CALENDAR)).thenReturn(LEADER_TOKEN);
        when(integrations.findAccessToken(workspaceId, leaderId, Provider.GOOGLE_CALENDAR))
            .thenReturn(Optional.of(LEADER_TOKEN));
        when(integrations.findAccessToken(workspaceId, memberId, Provider.GOOGLE_CALENDAR))
            .thenReturn(Optional.of(MEMBER_TOKEN));
        when(integrations.hasConnection(workspaceId, memberId, Provider.GOOGLE_CALENDAR)).thenReturn(true);
        when(calendar.getBusyTimes(anyString(), any(), any())).thenReturn(List.of());

        when(calendar.insertEvent(anyString(), anyString(), any(EventWrite.class), anyBoolean())).thenAnswer(call -> {
            EventWrite w = call.getArgument(2);
            String id = "team-" + UUID.randomUUID();
            return new CalendarEvent(id, w.title(), w.description(), w.startAt(), w.endAt(), false,
                w.attendeeEmails(), "https://calendar.example/" + id, "https://meet.example/x", DAY, "uid-" + id);
        });
        when(calendar.patchEvent(anyString(), anyString(), anyString(), any(EventWrite.class))).thenAnswer(call -> {
            EventWrite w = call.getArgument(3);
            String id = call.getArgument(2);
            return new CalendarEvent(id, w.title(), w.description(), w.startAt(), w.endAt(), false,
                w.attendeeEmails(), "https://calendar.example/" + id, "https://meet.example/x", DAY, "uid-" + id);
        });
        when(calendar.importCopy(eq(MEMBER_TOKEN), eq("primary"), anyString(), any(EventWrite.class)))
            .thenAnswer(call -> new CalendarEvent("copy-" + UUID.randomUUID(), "사본", null, DAY, DAY.plusHours(1),
                false, List.of(), null, null, DAY, call.getArgument(2)));
    }

    @Test
    void 회의를_확정하면_팀원_캘린더에_사본을_넣고_팀장_캘린더에는_넣지_않는다() {
        MeetingDetailResponse confirmed = confirm(meeting("주간 회의"), DAY.withHour(10), true);

        verify(calendar).importCopy(eq(MEMBER_TOKEN), eq("primary"), anyString(), any(EventWrite.class));
        verify(calendar, never()).importCopy(eq(LEADER_TOKEN), anyString(), anyString(), any(EventWrite.class));
        assertThat(copies(confirmed.calendarRefId())).isEqualTo(1L);
    }

    @Test
    void 확정_회의를_고치면_사본도_고치고_취소하면_사본도_지운다() {
        MeetingDetailResponse confirmed = confirm(meeting("주간 회의"), DAY.withHour(10), true);

        meetings.updateMeeting(workspaceId, confirmed.meetingId(), leaderId,
            new MeetingUpdateRequest("바뀐 회의", null, null, null, null, null, null));
        verify(calendar).patchCopy(eq(MEMBER_TOKEN), eq("primary"), anyString(), any(EventWrite.class));

        meetings.cancelMeeting(workspaceId, confirmed.meetingId(), leaderId);
        verify(calendar).deleteCopy(eq(MEMBER_TOKEN), eq("primary"), anyString());
        assertThat(copies(confirmed.calendarRefId())).isZero();
    }

    @Test
    void 다른_확정_회의와_겹치면_확정을_막고_겹치는_사람을_알려_준다() {
        confirm(meeting("먼저 잡힌 회의"), DAY.withHour(10), true);
        Long second = meeting("나중 회의");

        List<ConflictResponse> conflicts = meetings.getConflicts(workspaceId, second, DAY.withHour(10));
        assertThat(conflicts).extracting(ConflictResponse::userId).containsExactlyInAnyOrder(leaderId, memberId);
        assertThat(conflicts.get(0).reasons()).containsExactly("먼저 잡힌 회의");

        assertThatThrownBy(() -> confirm(second, DAY.withHour(10), false))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.MEETING_TIME_CONFLICT);
        assertThat(meetings.getConflicts(workspaceId, second, DAY.withHour(14))).isEmpty();
        assertThat(confirm(second, DAY.withHour(10), true).status().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void 응답한_뒤에_다른_회의가_확정되면_추천에서_그_시간을_불가능으로_다시_계산한다() {
        Long second = meeting("나중 회의");
        List<SlotInput> slots = List.of(DAY.withHour(10), DAY.withHour(10).withMinute(30),
                DAY.withHour(14), DAY.withHour(14).withMinute(30)).stream()
            .map(t -> new SlotInput(t, SlotPreference.AVAILABLE)).toList();
        meetings.saveAvailability(workspaceId, second, leaderId, new AvailabilitySaveRequest(slots));
        meetings.saveAvailability(workspaceId, second, memberId, new AvailabilitySaveRequest(slots));

        confirm(meeting("먼저 잡힌 회의"), DAY.withHour(10), true);

        List<TimeCandidateResponse> recommended = meetings.recommendTimes(workspaceId, second, leaderId);
        assertThat(recommended.get(0).startAt()).isEqualTo(DAY.withHour(14));
    }

    private Long meeting(String title) {
        return meetings.createMeeting(workspaceId, leaderId, new MeetingCreateRequest(title, 60,
            DAY, DAY.withHour(18), List.of(leaderId, memberId), "온라인", List.of())).meetingId();
    }

    private MeetingDetailResponse confirm(Long meetingId, LocalDateTime startAt, boolean force) {
        return meetings.confirmMeeting(workspaceId, meetingId, leaderId, startAt, force);
    }

    private Long copies(Long contentRefId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM calendar_event_copy WHERE content_ref_id = ?",
            Long.class, contentRefId);
    }
}
