package com.togather.task.dto;

import com.togather.common.enums.TaskStatus;
import java.time.LocalDate;
import java.time.LocalTime;

public record TaskUpdateRequest(
    String title,
    String description,
    Long assigneeId,
    LocalDate dueDate,
    TaskStatus status,
    /** 마감일과 함께 보낼 때만 바꾼다. (마감일을 보내며 비우면 시각도 지운다) */
    LocalTime dueTime
) {

    public TaskUpdateRequest(String title, String description, Long assigneeId, LocalDate dueDate,
                             TaskStatus status) {
        this(title, description, assigneeId, dueDate, status, null);
    }
}
