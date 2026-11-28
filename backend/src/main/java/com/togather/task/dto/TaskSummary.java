package com.togather.task.dto;

import com.togather.common.enums.RiskLevel;
import com.togather.common.enums.TaskStatus;
import com.togather.task.domain.Task;
import java.time.LocalDate;
import java.time.LocalTime;

/** 다른 파트에 제공하는 Task 요약 (docs/shared-interfaces.md, C 담당) */
public record TaskSummary(
    Long taskId,
    Long workspaceId,
    String title,
    Long assigneeId,
    LocalDate dueDate,
    TaskStatus status,
    int progress,
    RiskLevel riskLevel,
    LocalTime dueTime
) {

    public TaskSummary(Long taskId, Long workspaceId, String title, Long assigneeId, LocalDate dueDate,
                       TaskStatus status, int progress, RiskLevel riskLevel) {
        this(taskId, workspaceId, title, assigneeId, dueDate, status, progress, riskLevel, null);
    }

    public static TaskSummary from(Task task, LocalDate today) {
        return new TaskSummary(
            task.getTaskId(),
            task.getWorkspaceId(),
            task.getTitle(),
            task.getAssigneeId(),
            task.getDueDate(),
            task.getStatus(),
            task.calculateProgress(),
            task.evaluateRisk(today),
            task.getDueTime()
        );
    }
}
