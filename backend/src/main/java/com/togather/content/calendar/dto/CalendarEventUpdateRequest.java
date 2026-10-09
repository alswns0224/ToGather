package com.togather.content.calendar.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 일정 수정 API 요청. null인 항목은 바꾸지 않는다. */
public record CalendarEventUpdateRequest(
        @Size(min = 1, max = 200) String title,
        LocalDateTime startAt,
        LocalDateTime endAt,
        List<Long> participantIds,
        @Size(max = 5000) String description
) {
}
