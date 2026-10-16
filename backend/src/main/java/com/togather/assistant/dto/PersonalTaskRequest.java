package com.togather.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 개인 할 일 직접 추가·수정 (UI-13 내 할 일) */
public record PersonalTaskRequest(@NotBlank @Size(max = 200) String title, LocalDate dueDate) {
}
