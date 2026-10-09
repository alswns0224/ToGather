package com.togather.content.calendar.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CD-DTO CalendarEventRequest. 다른 파트가 팀 캘린더에 일정을 등록할 때 쓴다. (docs/shared-interfaces.md)
 *
 * @param endAt        null이면 시작 후 1시간
 * @param participants 참석자 사용자 ID
 */
public record CalendarEventRequest(String title, LocalDateTime startAt, LocalDateTime endAt,
                                   List<Long> participants, String description) {
}
