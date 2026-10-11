package com.togather.analysis.service;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 후속 작업 → Task 생성 (SD-08-04). C의 TaskService.createTask(workspaceId, TaskCommand)를 호출하는 자리다.
 *
 * <p>TODO(C-01 완료 후): TaskService를 주입받아 아래처럼 바꾼다. (docs/shared-interfaces.md 3. C가 제공)
 * <pre>
 * return taskService.createTask(workspaceId, new TaskCommand(task.title(), task.description(), task.assigneeId(),
 *         task.dueDate(), TaskStatus.TODO, List.of(), task.originAnalysisItemId()));
 * </pre>
 * 그 전까지는 TASK_SERVICE_UNAVAILABLE을 던지고, 실패 단계로 기록되어 나중에 다시 시도할 수 있다.
 */
@Slf4j
@Component
public class TaskGateway {

    public record FollowUpTask(String title, String description, Long assigneeId, LocalDate dueDate,
                               Long originAnalysisItemId) {
    }

    /** @return 생성된 Task ID */
    public Long createTask(Long workspaceId, FollowUpTask task) {
        log.info("Task 생성 요청 보류 (TaskService.createTask 미구현): workspaceId={}, itemId={}",
                workspaceId, task.originAnalysisItemId());
        throw new BusinessException(ErrorCode.TASK_SERVICE_UNAVAILABLE);
    }
}
