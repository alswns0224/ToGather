package com.togather.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.togather.common.auth.LoginUserArgumentResolver;
import com.togather.common.enums.MeetingStatus;
import com.togather.common.enums.Provider;
import com.togather.common.enums.SlotPreference;
import com.togather.common.enums.TaskStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.client.GoogleCalendarClient;
import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.client.GoogleCalendarClient.EventWrite;
import com.togather.content.service.ContentRefService;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.TargetAccess;
import com.togather.meeting.dto.AvailabilitySaveRequest;
import com.togather.meeting.dto.MeetingCreateRequest;
import com.togather.meeting.dto.MeetingDetailResponse;
import com.togather.meeting.dto.MeetingUpdateRequest;
import com.togather.support.IntegrationTestSupport;
import com.togather.task.dto.TaskCommand;
import com.togather.task.service.TaskService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class ConfirmedMeetingUpdateTest extends IntegrationTestSupport {

    private static final String CALENDAR = "meeting-team-calendar";
    private static final String TOKEN = "test-calendar-token";
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 23, 10, 0);

    @Autowired private MeetingService meetings;
    @Autowired private TaskService tasks;
    @Autowired private ContentRefService contents;
    @Autowired private WebApplicationContext context;
    @MockitoBean private IntegrationService integrations;
    @MockitoBean private GoogleCalendarClient calendar;

    private final Map<String, CalendarEvent> remote = new ConcurrentHashMap<>();
    private Long userId;
    private Long memberId;
    private Long workspaceId;
    private MockMvc mvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        userId = createUser("회의 생성자", UUID.randomUUID() + "@test.example");
        memberId = createUser("기존 참여자", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(userId);
        addMember(workspaceId, memberId, "MEMBER");
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        session = sessionFor(userId);
        var access = new TargetAccess(1L, userId, TOKEN, List.of(CALENDAR));
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR)).thenReturn(Optional.of(access));
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR, CALENDAR))
            .thenReturn(Optional.of(access));
        when(integrations.getAccessToken(workspaceId, userId, Provider.GOOGLE_CALENDAR)).thenReturn(TOKEN);
        when(calendar.insertEvent(anyString(), anyString(), any(EventWrite.class), eq(true))).thenAnswer(call -> {
            EventWrite write = call.getArgument(2);
            String id = UUID.randomUUID().toString();
            var event = new CalendarEvent(id, write.title(), write.description(), write.startAt(), write.endAt(),
                false, write.attendeeEmails(), "https://calendar.example/" + id, "https://meet.example/" + id, START);
            remote.put(id, event);
            return event;
        });
        when(calendar.getEvent(anyString(), anyString(), anyString())).thenAnswer(call -> requireEvent(call.getArgument(2)));
        when(calendar.patchEvent(anyString(), anyString(), anyString(), any(EventWrite.class)))
            .thenAnswer(call -> patchRemote(call.getArgument(2), call.getArgument(3)));
        doAnswer(call -> { remote.remove((String) call.getArgument(2)); return null; })
            .when(calendar).deleteEvent(anyString(), anyString(), anyString());
    }

    @Test
    void 확정_회의의_정보_시간_참여자를_바꾸면_같은_Calendar_일정을_수정한다() throws Exception {
        MeetingDetailResponse original = confirmed();
        Long newMember = createUser("새 참여자", UUID.randomUUID() + "@test.example");
        addMember(workspaceId, newMember, "MEMBER");
        LocalDateTime moved = START.plusDays(10).withHour(14);
        mvc.perform(patch(path(original.meetingId())).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  변경된 회의  \",\"durationMinutes\":90,\"startAt\":\"" + moved
                    + "\",\"participantIds\":[" + userId + "," + newMember + "],\"meetingMode\":\"오프라인\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.title").value("변경된 회의"))
            .andExpect(jsonPath("$.data.calendarRefId").value(original.calendarRefId()))
            .andExpect(jsonPath("$.data.meetLink").value(original.meetLink()))
            .andExpect(jsonPath("$.data.calendarSyncFailed").value(false));

        MeetingDetailResponse updated = meetings.getMeeting(workspaceId, original.meetingId(), userId);
        assertThat(updated.startAt()).isEqualTo(moved);
        assertThat(updated.endAt()).isEqualTo(moved.plusMinutes(90));
        assertThat(updated.candidateStartAt()).isEqualTo(original.candidateStartAt());
        assertThat(updated.participants()).extracting(participant -> participant.userId())
            .containsExactlyInAnyOrder(userId, newMember);
        CalendarEvent event = onlyEvent();
        assertThat(event.title()).isEqualTo("변경된 회의");
        assertThat(event.startAt()).isEqualTo(updated.startAt());
        assertThat(event.endAt()).isEqualTo(updated.endAt());
        assertThat(event.description()).contains("진행 방식: 오프라인");
        assertThat(event.attendeeEmails()).hasSize(2);
        assertThat(contents.getContentRef(original.calendarRefId()).title()).isEqualTo("변경된 회의");
        verify(calendar, times(1)).insertEvent(anyString(), anyString(), any(), anyBoolean());
        verify(calendar).patchEvent(eq(TOKEN), eq(CALENDAR), eq(event.id()), any());
    }

    @Test
    void 제목만_수정하면_확정_시각과_참여자와_Meet_링크를_유지한다() throws Exception {
        MeetingDetailResponse original = confirmed();
        update(original.meetingId(), "{\"title\":\"제목만 변경\"}");
        MeetingDetailResponse updated = meetings.getMeeting(workspaceId, original.meetingId(), userId);
        assertThat(updated.startAt()).isEqualTo(original.startAt());
        assertThat(updated.endAt()).isEqualTo(original.endAt());
        assertThat(updated.participants()).isEqualTo(original.participants());
        assertThat(updated.meetLink()).isEqualTo(original.meetLink());
        assertThat(updated.calendarRefId()).isEqualTo(original.calendarRefId());
        assertThat(onlyEvent().title()).isEqualTo("제목만 변경");
    }

    @Test
    void 소요_시간만_수정해도_Calendar_종료_시각을_함께_바꾼다() throws Exception {
        MeetingDetailResponse original = confirmed();
        update(original.meetingId(), "{\"durationMinutes\":120}");
        assertThat(onlyEvent().startAt()).isEqualTo(original.startAt());
        assertThat(onlyEvent().endAt()).isEqualTo(original.startAt().plusHours(2));
    }

    @Test
    void Calendar_수정_실패는_회의_변경을_보존하고_재시도는_동일_일정에_최신값을_반영한다() throws Exception {
        MeetingDetailResponse original = confirmed();
        when(calendar.patchEvent(anyString(), anyString(), anyString(), any()))
            .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));
        update(original.meetingId(), "{\"title\":\"보존할 변경\",\"durationMinutes\":90,\"participantIds\":[" + userId + "]}");
        MeetingDetailResponse failed = meetings.getMeeting(workspaceId, original.meetingId(), userId);
        assertThat(failed.title()).isEqualTo("보존할 변경");
        assertThat(failed.durationMinutes()).isEqualTo(90);
        assertThat(failed.participants()).hasSize(1);
        assertThat(failed.calendarSyncFailed()).isTrue();
        assertThat(failed.calendarRefId()).isEqualTo(original.calendarRefId());
        assertThat(onlyEvent().title()).isEqualTo(original.title());

        doAnswer(call -> patchRemote(call.getArgument(2), call.getArgument(3)))
            .when(calendar).patchEvent(anyString(), anyString(), anyString(), any());
        retry(original.meetingId());
        assertThat(onlyEvent().title()).isEqualTo("보존할 변경");
        assertThat(onlyEvent().endAt()).isEqualTo(START.plusMinutes(90));
        assertThat(onlyEvent().attendeeEmails()).hasSize(1);
        assertThat(meetings.getMeeting(workspaceId, original.meetingId(), userId).calendarSyncFailed()).isFalse();
        verify(calendar, times(1)).insertEvent(anyString(), anyString(), any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"REAUTH_REQUIRED", "CONNECTION_NOT_FOUND", "CONNECTION_IN_USE"})
    void 토큰이나_연결_실패도_회의_변경을_롤백하지_않는다(ErrorCode error) throws Exception {
        MeetingDetailResponse original = confirmed();
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR, CALENDAR))
            .thenThrow(new BusinessException(error));
        update(original.meetingId(), "{\"title\":\"연결 복구 후 반영할 회의\"}");
        MeetingDetailResponse failed = meetings.getMeeting(workspaceId, original.meetingId(), userId);
        assertThat(failed.title()).isEqualTo("연결 복구 후 반영할 회의");
        assertThat(failed.calendarSyncFailed()).isTrue();
        assertThat(failed.calendarRefId()).isEqualTo(original.calendarRefId());
        verify(calendar, never()).patchEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void 최초_등록_실패로_일정이_없어도_수정_후_최신_내용으로_등록할_수_있다() throws Exception {
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR))
            .thenThrow(new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
        MeetingDetailResponse original = confirmed();
        assertThat(original.calendarRefId()).isNull();
        assertThat(original.calendarSyncFailed()).isTrue();
        doReturn(Optional.of(new TargetAccess(1L, userId, TOKEN, List.of(CALENDAR))))
            .when(integrations).findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR);
        update(original.meetingId(), "{\"title\":\"연결 후 수정\",\"durationMinutes\":30}");
        assertThat(onlyEvent().title()).isEqualTo("연결 후 수정");
        assertThat(onlyEvent().endAt()).isEqualTo(START.plusMinutes(30));
        assertThat(meetings.getMeeting(workspaceId, original.meetingId(), userId).calendarSyncFailed()).isFalse();
    }

    @Test
    void 원본이_삭제돼도_자동으로_중복_등록하지_않고_명시적_재시도에서만_새로_등록한다() throws Exception {
        MeetingDetailResponse original = confirmed();
        remote.clear();
        update(original.meetingId(), "{\"title\":\"삭제 후 변경\"}");
        MeetingDetailResponse failed = meetings.getMeeting(workspaceId, original.meetingId(), userId);
        assertThat(failed.calendarSyncFailed()).isTrue();
        assertThat(failed.calendarRefId()).isEqualTo(original.calendarRefId());
        assertThat(contents.getOwner(original.calendarRefId()).isOriginDeleted()).isTrue();
        assertThat(remote).isEmpty();
        verify(calendar, times(1)).insertEvent(anyString(), anyString(), any(), anyBoolean());
        retry(original.meetingId());
        MeetingDetailResponse retried = meetings.getMeeting(workspaceId, original.meetingId(), userId);
        assertThat(retried.calendarRefId()).isNotEqualTo(original.calendarRefId());
        assertThat(retried.calendarSyncFailed()).isFalse();
        assertThat(onlyEvent().title()).isEqualTo("삭제 후 변경");
        assertThat(contents.getOwner(original.calendarRefId()).isOriginDeleted()).isTrue();
    }

    @Test
    void 다른_기본_대상을_선택했어도_기존_일정의_원래_캘린더에_반영한다() throws Exception {
        MeetingDetailResponse original = confirmed();
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR))
            .thenReturn(Optional.of(new TargetAccess(2L, memberId, "other-token", List.of("other-calendar"))));
        update(original.meetingId(), "{\"title\":\"원래 캘린더에 반영\"}");
        verify(calendar).patchEvent(eq(TOKEN), eq(CALENDAR), anyString(), any());
        verify(calendar, never()).patchEvent(anyString(), eq("other-calendar"), anyString(), any());
        assertThat(contents.getChannelId(original.calendarRefId())).isEqualTo(CALENDAR);
    }

    @Test
    void 원래_대상을_해제했으면_다른_캘린더로_대체하지_않는다() throws Exception {
        MeetingDetailResponse original = confirmed();
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR, CALENDAR))
            .thenReturn(Optional.empty());
        update(original.meetingId(), "{\"title\":\"대상 복구 대기\"}");
        assertThat(meetings.getMeeting(workspaceId, original.meetingId(), userId).calendarSyncFailed()).isTrue();
        verify(calendar, never()).patchEvent(anyString(), anyString(), anyString(), any());
        assertThat(onlyEvent().title()).isEqualTo(original.title());
    }

    @Test
    void 다른_관리자는_개인_일정을_복제하지_않고_원래_소유자가_재시도할_수_있다() throws Exception {
        when(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR)).thenReturn(Optional.empty());
        MeetingDetailResponse original = confirmed();
        Long admin = createUser("다른 관리자", UUID.randomUUID() + "@test.example");
        addMember(workspaceId, admin, "ADMIN");
        mvc.perform(patch(path(original.meetingId())).session(sessionFor(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"개인 일정 소유자가 반영\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.calendarSyncFailed").value(true));
        mvc.perform(post(path(original.meetingId()) + "/calendar-retry").session(sessionFor(admin)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.calendarSyncFailed").value(true));
        verify(calendar, times(1)).insertEvent(anyString(), anyString(), any(), anyBoolean());
        assertThat(contents.getOwner(original.calendarRefId()).isOriginDeleted()).isFalse();
        retry(original.meetingId());
        assertThat(meetings.getMeeting(workspaceId, original.meetingId(), userId).calendarSyncFailed()).isFalse();
        assertThat(onlyEvent().title()).isEqualTo("개인 일정 소유자가 반영");
    }

    @Test
    void 확정_후_안건_추가_변경_삭제도_Calendar_설명에_반영한다() {
        MeetingDetailResponse original = confirmed();
        Long first = task("첫 번째 안건");
        Long second = task("바뀐 안건");
        var added = meetings.addAgenda(workspaceId, original.meetingId(), userId, first);
        assertThat(onlyEvent().description()).contains("첫 번째 안건");
        meetings.changeAgenda(workspaceId, original.meetingId(), added.agendas().getFirst().agendaId(), userId, second);
        assertThat(onlyEvent().description()).contains("바뀐 안건").doesNotContain("첫 번째 안건");
        meetings.deleteAgenda(workspaceId, original.meetingId(), added.agendas().getFirst().agendaId(), userId);
        assertThat(onlyEvent().description()).doesNotContain("안건");
        verify(calendar, times(1)).insertEvent(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void 수정과_취소가_겹쳐도_순서대로_처리하고_취소된_일정을_되살리지_않는다() throws Exception {
        MeetingDetailResponse original = confirmed();
        var patchStarted = new CountDownLatch(1);
        var releasePatch = new CountDownLatch(1);
        var cancelStarted = new CountDownLatch(1);
        when(calendar.patchEvent(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            patchStarted.countDown();
            if (!releasePatch.await(10, TimeUnit.SECONDS)) throw new AssertionError("수정 대기 초과");
            return patchRemote(call.getArgument(2), call.getArgument(3));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var update = executor.submit(() -> meetings.updateMeeting(workspaceId, original.meetingId(), userId,
                    new MeetingUpdateRequest("동시 수정", null, null, null, null, null, null)));
                assertThat(patchStarted.await(10, TimeUnit.SECONDS)).isTrue();
                var cancel = executor.submit(() -> {
                    cancelStarted.countDown();
                    meetings.cancelMeeting(workspaceId, original.meetingId(), userId);
                });
                assertThat(cancelStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> cancel.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releasePatch.countDown();
                update.get(10, TimeUnit.SECONDS);
                cancel.get(10, TimeUnit.SECONDS);
            } finally {
                releasePatch.countDown();
            }
        }
        assertThat(meetings.getMeeting(workspaceId, original.meetingId(), userId).status()).isEqualTo(MeetingStatus.CANCELED);
        assertThat(remote).isEmpty();
    }

    @Test
    void 조율_중_기간_수정은_응답과_후보를_초기화하고_Calendar를_생성하지_않는다() throws Exception {
        MeetingDetailResponse original = scheduling();
        meetings.saveAvailability(workspaceId, original.meetingId(), userId, new AvailabilitySaveRequest(List.of(
            new AvailabilitySaveRequest.SlotInput(START, SlotPreference.AVAILABLE),
            new AvailabilitySaveRequest.SlotInput(START.plusMinutes(30), SlotPreference.AVAILABLE))));
        meetings.recommendTimes(workspaceId, original.meetingId(), userId);
        assertThat(count("meeting_time_candidate", original.meetingId())).isPositive();
        update(original.meetingId(), "{\"candidateStartAt\":\"" + START.plusDays(1) + "\",\"candidateEndAt\":\""
            + START.plusDays(2) + "\"}");
        assertThat(count("availability_slot", original.meetingId())).isZero();
        assertThat(count("meeting_time_candidate", original.meetingId())).isZero();
        assertThat(meetings.getMeeting(workspaceId, original.meetingId(), userId).participants())
            .noneMatch(participant -> participant.hasResponded());
        verify(calendar, never()).insertEvent(anyString(), anyString(), any(), anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"title\":\" \"}", "{\"durationMinutes\":14}", "{\"durationMinutes\":481}",
        "{\"candidateStartAt\":\"2026-12-01T10:00:00\"}", "{\"startAt\":\"bad-date\"}"})
    void 잘못된_확정_회의_수정은_로컬과_Calendar_모두_변경하지_않는다(String body) throws Exception {
        MeetingDetailResponse original = confirmed();
        mvc.perform(patch(path(original.meetingId())).session(session).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        assertThat(meetings.getMeeting(workspaceId, original.meetingId(), userId).title()).isEqualTo(original.title());
        verify(calendar, never()).patchEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void 잘못된_참여자는_회의_메타데이터_수정도_롤백한다() throws Exception {
        MeetingDetailResponse original = confirmed();
        Long outsider = createUser("외부인", UUID.randomUUID() + "@test.example");
        mvc.perform(patch(path(original.meetingId())).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"저장 금지\",\"participantIds\":[" + outsider + "]}"))
            .andExpect(status().isNotFound());
        assertThat(meetings.getMeeting(workspaceId, original.meetingId(), userId).title()).isEqualTo(original.title());
        verify(calendar, never()).patchEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void 수정은_로그인_멤버십_관리_권한과_워크스페이스_소속을_검사한다() throws Exception {
        MeetingDetailResponse original = confirmed();
        Long outsider = createUser("외부인", UUID.randomUUID() + "@test.example");
        String body = "{\"title\":\"차단해야 할 변경\"}";
        mvc.perform(patch(path(original.meetingId())).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(patch(path(original.meetingId())).session(sessionFor(outsider))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(patch(path(original.meetingId())).session(sessionFor(memberId))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        Long otherWorkspace = createWorkspace(userId);
        mvc.perform(patch("/api/workspaces/" + otherWorkspace + "/meetings/" + original.meetingId()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertThat(onlyEvent().title()).isEqualTo(original.title());
        verify(calendar, never()).patchEvent(anyString(), anyString(), anyString(), any());
    }

    @Test
    void 취소된_회의_수정과_정상_일정의_중복_재시도를_차단한다() throws Exception {
        MeetingDetailResponse original = confirmed();
        mvc.perform(post(path(original.meetingId()) + "/calendar-retry").session(session))
            .andExpect(status().isBadRequest());
        meetings.cancelMeeting(workspaceId, original.meetingId(), userId);
        mvc.perform(patch(path(original.meetingId())).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"취소 후 변경\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path(original.meetingId()) + "/calendar-retry").session(session))
            .andExpect(status().isBadRequest());
        assertThat(remote).isEmpty();
    }

    private MeetingDetailResponse scheduling() {
        return meetings.createMeeting(workspaceId, userId, new MeetingCreateRequest("원래 회의", 60,
            START, START.plusDays(1), List.of(memberId), "온라인", List.of()));
    }

    private MeetingDetailResponse confirmed() {
        var created = scheduling();
        return meetings.confirmMeeting(workspaceId, created.meetingId(), userId, START);
    }

    private void update(Long meetingId, String body) throws Exception {
        mvc.perform(patch(path(meetingId)).session(session).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
    }

    private void retry(Long meetingId) throws Exception {
        mvc.perform(post(path(meetingId) + "/calendar-retry").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.calendarSyncFailed").value(false));
    }

    private Long task(String title) {
        return tasks.createTask(workspaceId, new TaskCommand(title, null, userId, null,
            TaskStatus.IN_PROGRESS, List.of(), null));
    }

    private Long count(String table, Long meetingId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE meeting_id = ?", Long.class, meetingId);
    }

    private CalendarEvent onlyEvent() {
        assertThat(remote).hasSize(1);
        return remote.values().iterator().next();
    }

    private CalendarEvent requireEvent(String id) {
        CalendarEvent event = remote.get(id);
        if (event == null) throw new BusinessException(ErrorCode.CALENDAR_EVENT_NOT_FOUND);
        return event;
    }

    private CalendarEvent patchRemote(String id, EventWrite write) {
        CalendarEvent current = requireEvent(id);
        var updated = new CalendarEvent(id, write.title(), write.description(), write.startAt(), write.endAt(),
            false, write.attendeeEmails(), current.htmlLink(), current.meetLink(), current.createdAt());
        remote.put(id, updated);
        return updated;
    }

    private MockHttpSession sessionFor(Long id) {
        var result = new MockHttpSession(context.getServletContext());
        result.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, id);
        return result;
    }

    private String path(Long meetingId) {
        return "/api/workspaces/" + workspaceId + "/meetings/" + meetingId;
    }
}
