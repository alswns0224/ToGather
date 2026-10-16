package com.togather.task.dto;

import com.togather.common.enums.TaskStatus;
import java.time.LocalDate;

public record TaskUpdateRequest(
    String title,
    String description,
    Long assigneeId,
    LocalDate dueDate,
    TaskStatus status
) {
}
