package com.togather.analysis.service;

import com.togather.common.enums.TaskStatus;
import com.togather.task.dto.TaskCommand;
import com.togather.task.service.TaskService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * B가 쓰는 C의 Task 기능 (docs/shared-interfaces.md 3. C가 제공).
 * 후속 작업 → Task 생성 (SD-08-04), 내 담당 Task (AI 업무 보조 중복 후보 제외, 내 할 일)
 */
@Component
@RequiredArgsConstructor
public class TaskGateway {

    private final TaskService taskService;

    public record FollowUpTask(String title, String description, Long assigneeId, LocalDate dueDate,
                               Long originAnalysisItemId) {
    }

    public record MyTask(Long taskId, Long workspaceId, String title, LocalDate dueDate) {
    }

    /** 끝나지 않은 내 담당 Task */
    public List<MyTask> getMyTasks(Long userId) {
        return taskService.getMyTasks(userId).stream()
                .filter(t -> t.status() != TaskStatus.DONE)
                .map(t -> new MyTask(t.taskId(), t.workspaceId(), t.title(), t.dueDate()))
                .toList();
    }

    /** @return 생성된 Task ID. 근거 결정사항(originAnalysisItemId)을 함께 저장한다. */
    public Long createTask(Long workspaceId, FollowUpTask task) {
        return taskService.createTask(workspaceId, new TaskCommand(task.title(), task.description(),
                task.assigneeId(), task.dueDate(), TaskStatus.IN_PROGRESS, List.of(), task.originAnalysisItemId()));
    }
}
