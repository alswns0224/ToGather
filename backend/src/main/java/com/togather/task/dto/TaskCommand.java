package com.togather.task.dto;

import com.togather.common.enums.TaskStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record TaskCommand(
    String title,
    String description,
    Long assigneeId,
    LocalDate dueDate,
    TaskStatus status,
    List<DeliverableInput> deliverables,
    Long originAnalysisItemId,
    LocalTime dueTime
) {

    public TaskCommand(String title, String description, Long assigneeId, LocalDate dueDate, TaskStatus status,
                       List<DeliverableInput> deliverables, Long originAnalysisItemId) {
        this(title, description, assigneeId, dueDate, status, deliverables, originAnalysisItemId, null);
    }
}
