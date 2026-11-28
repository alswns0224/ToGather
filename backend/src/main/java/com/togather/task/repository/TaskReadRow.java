package com.togather.task.repository;

import com.togather.common.enums.TaskStatus;
import com.togather.task.domain.Task;
import com.togather.task.dto.TaskSummary;
import java.time.LocalDate;
import java.time.LocalTime;

/** 제출 파일·본문을 읽지 않고 Task 한 건과 산출물 개수만 조회하는 내부 projection. */
public record TaskReadRow(Long taskId, Long workspaceId, String title, Long assigneeId, LocalDate dueDate,
                          TaskStatus status, Long requiredCount, Long submittedCount, LocalTime dueTime) {

    public TaskSummary summary(LocalDate today) {
        int progress = Task.calculateProgress(status, requiredCount, submittedCount);
        return new TaskSummary(taskId, workspaceId, title, assigneeId, dueDate, status, progress,
                Task.evaluateRisk(status, dueDate, progress, today), dueTime);
    }
}
