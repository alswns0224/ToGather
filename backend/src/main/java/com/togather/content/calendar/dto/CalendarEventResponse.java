package com.togather.content.calendar.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 일정 응답 (UI-07)
 *
 * @param meetingId 확정된 회의 일정이면 회의 ID (일반 일정과 구분 표시)
 * @param personal  팀 캘린더가 아니라 내 기본 캘린더의 일정인지
 */
public record CalendarEventResponse(
        Long contentRefId,
        String title,
        LocalDateTime startAt,
        LocalDateTime endAt,
        boolean allDay,
        String description,
        List<String> attendees,
        String deepLink,
        String meetLink,
        Long meetingId,
        boolean personal
) {
}
