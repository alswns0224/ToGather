package com.togather.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 승인 전 수정: 제목, 일시(일정), 마감일(할 일) */
public record SuggestionUpdateRequest(@NotBlank @Size(max = 200) String title, LocalDateTime detectedDatetime,
                                      LocalDate dueDate) {
}
