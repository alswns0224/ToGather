package com.togather.content.calendar.dto;

import java.time.LocalDateTime;

/** CD-DTO PersonalEventRequest. AI 업무 보조에서 승인한 일정을 사용자 개인 캘린더에 등록한다. */
public record PersonalEventRequest(String title, LocalDateTime startAt, LocalDateTime endAt) {
}
