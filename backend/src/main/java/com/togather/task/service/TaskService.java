package com.togather.task.service;

import com.togather.common.enums.TaskStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.task.dto.TaskSummary;
import com.togather.task.repository.TaskRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-05 TaskService (C 담당).
 * A의 회의 안건에 필요한 조회 메서드(docs/shared-interfaces.md)만 먼저 만들어 두었다. C가 이어서 CRUD를 구현한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskSummary getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .map(task -> TaskSummary.from(task, LocalDate.now()))
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    /** 없는 ID는 결과에서 빠진다. */
    public List<TaskSummary> getTasks(Collection<Long> taskIds) {
        LocalDate today = LocalDate.now();
        return taskRepository.findAllById(taskIds).stream().map(t -> TaskSummary.from(t, today)).toList();
    }

    /** 회의 안건 후보: 진행 중인 Task (마감일 순) */
    public List<TaskSummary> getTasksInProgress(Long workspaceId) {
        LocalDate today = LocalDate.now();
        return taskRepository.findAllByWorkspaceIdAndStatusOrderByDueDateAsc(workspaceId, TaskStatus.IN_PROGRESS)
                .stream().map(t -> TaskSummary.from(t, today)).toList();
    }
}
