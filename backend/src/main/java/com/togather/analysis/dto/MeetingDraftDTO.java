package com.togather.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회의 제안 → Calendar 일정 초안 (SD-08-05). 등록 요청에도 같은 형식을 쓴다.
 *
 * @param endAt null이면 시작 후 1시간
 */
public record MeetingDraftDTO(@NotBlank @Size(max = 200) String title, @NotNull LocalDateTime startAt,
                              LocalDateTime endAt, List<Long> participantIds, @Size(max = 4000) String description) {
}
