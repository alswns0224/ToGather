package com.togather.content.calendar.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.domain.DateRange;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.calendar.client.GoogleCalendarClient;
import com.togather.content.calendar.client.GoogleCalendarClient.CalendarEvent;
import com.togather.content.calendar.client.GoogleCalendarClient.EventList;
import com.togather.content.calendar.client.GoogleCalendarClient.EventWrite;
import com.togather.content.calendar.dto.CalendarEventCreateRequest;
import com.togather.content.calendar.dto.CalendarEventRequest;
import com.togather.content.calendar.dto.CalendarEventResponse;
import com.togather.content.calendar.dto.CalendarEventUpdateRequest;
import com.togather.content.calendar.dto.PersonalEventRequest;
import com.togather.content.dto.ContentDetailDTO;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentOwner;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.meeting.domain.Meeting;
import com.togather.meeting.repository.MeetingRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google Calendar 일정 (UC-07-01 ~ 04).
 *
 * <p>회의 확정·가용 시간처럼 호출한 쪽이 캘린더 오류를 잡고 계속 진행하는 경우가 있으므로,
 * BusinessException이 나도 호출한 쪽 트랜잭션을 롤백 전용으로 만들지 않는다(noRollbackFor).</p>
 *
 * <ul>
 *   <li><b>팀 캘린더</b>: 연동 관리에서 캘린더를 대상으로 고른 연결이 있으면 그 캘린더를 쓴다.</li>
 *   <li>팀 캘린더가 없으면 요청한 사용자의 기본 캘린더(primary)를 쓴다.</li>
 *   <li>content_ref.channelId에 캘린더 ID, originId에 일정 ID를 저장한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = BusinessException.class)
public class CalendarContentService {

    private static final String PRIMARY = "primary";

    private final GoogleCalendarClient calendarClient;
    private final IntegrationService integrationService;
    private final ContentRefService contentRefService;
    private final UserService userService;
    private final MeetingRepository meetingRepository;

    /** 캘린더 접근 정보. ownerUserId가 있으면 개인 캘린더 */
    private record Access(String token, String calendarId, Long ownerUserId) {
    }

    // ===================== 화면용 (UI-07) =====================

    /** 기간 안의 일정 (월·주 조회) */
    @Transactional(noRollbackFor = BusinessException.class)
    public List<CalendarEventResponse> getEvents(Long workspaceId, Long userId, LocalDateTime from, LocalDateTime to) {
        if (!from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
        Access access = workspaceAccess(workspaceId, userId);
        EventList list = calendarClient.listEvents(access.token(), access.calendarId(), from, to);
        markCancelledDeleted(workspaceId, access, list.cancelledIds());
        List<CalendarEvent> events = list.events();
        List<ContentRefDTO> refs = events.stream().map(e -> save(workspaceId, access, e)).toList();
        Map<Long, Long> meetings = meetingIdsByCalendarRef(refs.stream().map(ContentRefDTO::contentRefId).toList());
        return IntStream.range(0, events.size())
                .mapToObj(i -> toResponse(refs.get(i).contentRefId(), events.get(i),
                        meetings.get(refs.get(i).contentRefId()), access.ownerUserId() != null))
                .toList();
    }

    @Transactional(noRollbackFor = BusinessException.class) // 원본 삭제 표시는 예외가 나도 남긴다
    public CalendarEventResponse getEvent(Long workspaceId, Long userId, Long contentRefId) {
        Access access = refAccess(workspaceId, userId, contentRefId);
        CalendarEvent event = fetchOrMarkDeleted(access, contentRefId);
        return toResponse(contentRefId, event, meetingIdsByCalendarRef(List.of(contentRefId)).get(contentRefId),
                access.ownerUserId() != null);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public CalendarEventResponse createEvent(Long workspaceId, Long userId, CalendarEventCreateRequest request) {
        validateRange(request.startAt(), request.endAt());
        return createInternal(workspaceId, userId, request.title(), request.startAt(), request.endAt(),
                request.participantIds(), request.description(), false);
    }

    @Transactional(noRollbackFor = BusinessException.class) // 원본 삭제 표시는 예외가 나도 남긴다
    public CalendarEventResponse updateEvent(Long workspaceId, Long userId, Long contentRefId,
                                             CalendarEventUpdateRequest request) {
        Access access = refAccess(workspaceId, userId, contentRefId);
        String eventId = contentRefService.getContentRef(contentRefId).externalId();
        CalendarEvent current = fetchOrMarkDeleted(access, contentRefId);
        LocalDateTime start = request.startAt() != null ? request.startAt() : current.startAt();
        LocalDateTime end = request.endAt() != null ? request.endAt() : current.endAt();
        validateRange(start, end);
        CalendarEvent updated = calendarClient.patchEvent(access.token(), access.calendarId(), eventId,
                new EventWrite(request.title(), request.description(), request.startAt(), request.endAt(),
                        request.participantIds() == null ? null : emails(request.participantIds())));
        contentRefService.updateContent(contentRefId, updated.title(), updated.description());
        return toResponse(contentRefId, updated, meetingIdsByCalendarRef(List.of(contentRefId)).get(contentRefId),
                access.ownerUserId() != null);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void deleteEvent(Long workspaceId, Long userId, Long contentRefId) {
        Access access = refAccess(workspaceId, userId, contentRefId);
        String eventId = contentRefService.getContentRef(contentRefId).externalId();
        calendarClient.deleteEvent(access.token(), access.calendarId(), eventId);
        contentRefService.markOriginDeleted(contentRefId);
    }

    // ===================== 다른 파트·회의 제공 (docs/shared-interfaces.md) =====================

    /** 팀 캘린더에 일정을 등록한다. (논의 분석의 회의 제안 SD-08-05 등) */
    @Transactional(noRollbackFor = BusinessException.class)
    public ContentRefDTO createContent(Long workspaceId, Long userId, CalendarEventRequest request) {
        LocalDateTime end = request.endAt() != null ? request.endAt() : request.startAt().plusHours(1);
        validateRange(request.startAt(), end);
        CalendarEventResponse created = createInternal(workspaceId, userId, request.title(), request.startAt(), end,
                request.participants(), request.description(), false);
        return contentRefService.getContentRef(created.contentRefId());
    }

    /** 회의 확정 시 Google Meet 링크와 함께 팀 캘린더에 등록한다. */
    @Transactional(noRollbackFor = BusinessException.class)
    public CalendarEventResponse createMeetingEvent(Long workspaceId, Long userId, String title, LocalDateTime startAt,
                                                    LocalDateTime endAt, List<Long> participantIds, String description) {
        return createInternal(workspaceId, userId, title, startAt, endAt, participantIds, description, true);
    }

    /** 사용자 개인 기본 캘린더에 일정을 등록한다. (AI 업무 보조 일정 승인 SD-13-01) */
    @Transactional(noRollbackFor = BusinessException.class)
    public ContentRefDTO createPersonalEvent(Long workspaceId, Long userId, PersonalEventRequest request) {
        LocalDateTime end = request.endAt() != null ? request.endAt() : request.startAt().plusHours(1);
        validateRange(request.startAt(), end);
        Access access = personalAccess(workspaceId, userId);
        CalendarEvent event = calendarClient.insertEvent(access.token(), access.calendarId(),
                new EventWrite(request.title(), null, request.startAt(), end, null), false);
        return save(workspaceId, access, event);
    }

    /** 통합 검색·협업 맥락에서 쓰는 일정 상세. 원본이 지워졌으면 보관 사본을 준다. */
    @Transactional(noRollbackFor = BusinessException.class)
    public ContentDetailDTO getContentDetail(Long contentRefId) {
        ContentOwner owner = contentRefService.getOwner(contentRefId);
        ContentRefDTO ref = contentRefService.getContentRef(contentRefId);
        if (!owner.isOriginDeleted()) {
            try {
                Access access = owner.ownerUserId() != null
                        ? personalAccess(owner.workspaceId(), owner.ownerUserId())
                        : teamAccess(owner.workspaceId());
                CalendarEvent event = calendarClient.getEvent(access.token(), access.calendarId(), ref.externalId());
                return new ContentDetailDTO(contentRefId, Provider.GOOGLE_CALENDAR, event.title(), event.description(),
                        event.htmlLink(), null, event.startAt(), event.attendeeEmails(), event.description(), false);
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.CALENDAR_EVENT_NOT_FOUND) {
                    throw e;
                }
                contentRefService.markOriginDeleted(contentRefId);
            }
        }
        ContentRefDTO saved = contentRefService.getContentRef(contentRefId);
        return new ContentDetailDTO(contentRefId, Provider.GOOGLE_CALENDAR, saved.title(), saved.content(),
                saved.deepLink(), null, null, List.of(), saved.content(), true);
    }

    /**
     * 사용자 기본 캘린더에서 일정이 있는 시간대 (회의 가용 시간 자동 표시, 명세서 getContentsByUser).
     * 캘린더를 연동하지 않았으면 빈 목록.
     */
    public List<DateRange> getBusyTimes(Long workspaceId, Long userId, DateRange period) {
        return integrationService.findAccessToken(workspaceId, userId, Provider.GOOGLE_CALENDAR)
                .map(token -> calendarClient.getBusyTimes(token, period.getStart(), period.getEnd()))
                .orElse(List.of());
    }

    /** 사용자가 이 워크스페이스에서 Google Calendar를 연동했는지 */
    public boolean isCalendarConnected(Long workspaceId, Long userId) {
        return integrationService.hasConnection(workspaceId, userId, Provider.GOOGLE_CALENDAR);
    }

    // ===================== 내부 =====================

    private CalendarEventResponse createInternal(Long workspaceId, Long userId, String title, LocalDateTime startAt,
                                                 LocalDateTime endAt, List<Long> participantIds, String description,
                                                 boolean withMeet) {
        Access access = workspaceAccess(workspaceId, userId);
        CalendarEvent event = calendarClient.insertEvent(access.token(), access.calendarId(),
                new EventWrite(title, description, startAt, endAt, emails(participantIds)), withMeet);
        ContentRefDTO ref = save(workspaceId, access, event);
        return toResponse(ref.contentRefId(), event, null, access.ownerUserId() != null);
    }

    /** 팀 캘린더가 있으면 팀 캘린더, 없으면 요청자의 기본 캘린더 */
    private Access workspaceAccess(Long workspaceId, Long userId) {
        return integrationService.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR)
                .map(t -> new Access(t.accessToken(), t.targetIds().get(0), null))
                .orElseGet(() -> personalAccess(workspaceId, userId));
    }

    private Access teamAccess(Long workspaceId) {
        return integrationService.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR)
                .map(t -> new Access(t.accessToken(), t.targetIds().get(0), null))
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
    }

    private Access personalAccess(Long workspaceId, Long userId) {
        return new Access(integrationService.getAccessToken(workspaceId, userId, Provider.GOOGLE_CALENDAR), PRIMARY, userId);
    }

    /** 저장된 일정의 접근 정보. 다른 워크스페이스나 다른 사람의 개인 일정이면 404 */
    private Access refAccess(Long workspaceId, Long userId, Long contentRefId) {
        ContentOwner owner = contentRefService.getOwner(contentRefId);
        ContentRefDTO ref = contentRefService.getContentRef(contentRefId);
        if (!owner.workspaceId().equals(workspaceId) || ref.provider() != Provider.GOOGLE_CALENDAR) {
            throw new BusinessException(ErrorCode.CALENDAR_EVENT_NOT_FOUND);
        }
        if (owner.isOriginDeleted()) {
            throw new BusinessException(ErrorCode.CALENDAR_EVENT_NOT_FOUND);
        }
        if (owner.ownerUserId() != null) {
            if (!owner.ownerUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.CALENDAR_EVENT_NOT_FOUND);
            }
            return personalAccess(workspaceId, userId);
        }
        return teamAccess(workspaceId);
    }

    private CalendarEvent fetchOrMarkDeleted(Access access, Long contentRefId) {
        String eventId = contentRefService.getContentRef(contentRefId).externalId();
        try {
            return calendarClient.getEvent(access.token(), access.calendarId(), eventId);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.CALENDAR_EVENT_NOT_FOUND) {
                contentRefService.markOriginDeleted(contentRefId);
            }
            throw e;
        }
    }

    /**
     * Google에서 지운 일정의 사본을 원본 삭제로 표시한다. (행은 남김: 회의·근거 연결 보존)
     * 같은 캘린더(팀/개인)의 사본만 표시한다.
     */
    private void markCancelledDeleted(Long workspaceId, Access access, List<String> cancelledIds) {
        for (String eventId : cancelledIds) {
            contentRefService.findByOrigin(workspaceId, Provider.GOOGLE_CALENDAR, eventId)
                    .filter(ref -> Objects.equals(contentRefService.getOwner(ref.contentRefId()).ownerUserId(),
                            access.ownerUserId()))
                    .ifPresent(ref -> contentRefService.markOriginDeleted(ref.contentRefId()));
        }
    }

    private ContentRefDTO save(Long workspaceId, Access access, CalendarEvent event) {
        return contentRefService.upsert(new ContentRefUpsert(workspaceId, access.ownerUserId(), Provider.GOOGLE_CALENDAR,
                ContentType.EVENT, event.id(), access.calendarId(), null, event.title(), event.description(),
                event.htmlLink(), event.createdAt() != null ? event.createdAt() : event.startAt()));
    }

    private List<String> emails(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userService.getUsers(userIds).stream().map(UserSummary::email).toList();
    }

    private Map<Long, Long> meetingIdsByCalendarRef(List<Long> contentRefIds) {
        if (contentRefIds.isEmpty()) {
            return Map.of();
        }
        return meetingRepository.findAllByCalendarRefIdIn(contentRefIds).stream()
                .collect(Collectors.toMap(Meeting::getCalendarRefId, Meeting::getMeetingId, (a, b) -> a));
    }

    private static CalendarEventResponse toResponse(Long contentRefId, CalendarEvent e, Long meetingId, boolean personal) {
        return new CalendarEventResponse(contentRefId, e.title(), e.startAt(), e.endAt(), e.allDay(), e.description(),
                e.attendeeEmails(), e.htmlLink(), e.meetLink(), meetingId, personal);
    }

    private static void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
    }
}
