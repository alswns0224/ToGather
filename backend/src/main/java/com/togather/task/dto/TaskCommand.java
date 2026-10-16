package com.togather.task.dto;

import com.togather.common.enums.TaskStatus;
import java.time.LocalDate;
import java.util.List;

public record TaskCommand(
    String title,
    String description,
    Long assigneeId,
    LocalDate dueDate,
    TaskStatus status,
    List<DeliverableInput> deliverables,
    Long originAnalysisItemId
) {
}
