package com.togather.task.dto;

import com.togather.common.enums.RiskLevel;
import com.togather.common.enums.TaskStatus;
import com.togather.task.domain.Task;
import java.time.LocalDate;

public record TaskDetailResponse(
    Long taskId,
    Long workspaceId,
    String title,
    String description,
    Long assigneeId,
    LocalDate dueDate,
    TaskStatus status,
    int progress,
    RiskLevel riskLevel
) {

    public static TaskDetailResponse from(Task task, LocalDate today) {
        return new TaskDetailResponse(
            task.getTaskId(),
            task.getWorkspaceId(),
            task.getTitle(),
            task.getDescription(),
            task.getAssigneeId(),
            task.getDueDate(),
            task.getStatus(),
            task.calculateProgress(),
            task.evaluateRisk(today)
        );
    }
}
