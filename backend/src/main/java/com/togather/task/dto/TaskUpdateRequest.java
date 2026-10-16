package com.togather.task.dto;

import com.togather.common.enums.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record TaskUpdateRequest(
    @NotBlank String title,
    String description,
    Long assigneeId,
    LocalDate dueDate,
    TaskStatus status
) {
}
