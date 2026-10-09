package com.togather.content.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 일정 등록 API 요청 (UI-07) */
public record CalendarEventCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt,
        List<Long> participantIds,
        @Size(max = 5000) String description
) {
}
