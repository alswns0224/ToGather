package com.togather.content.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 일정 등록 API 요청 (UI-07)
 *
 * @param personal true면 내 Google Calendar에만 등록하는 개인 일정 (참여자는 무시). 없거나 false면 팀 일정
 */
public record CalendarEventCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt,
        List<Long> participantIds,
        @Size(max = 5000) String description,
        Boolean personal
) {

    public CalendarEventCreateRequest(String title, LocalDateTime startAt, LocalDateTime endAt,
                                      List<Long> participantIds, String description) {
        this(title, startAt, endAt, participantIds, description, false);
    }

    public boolean isPersonal() {
        return Boolean.TRUE.equals(personal);
    }
}
