package com.togather.task.repository;

import com.togather.common.enums.TaskStatus;
import com.togather.task.domain.Task;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findAllByWorkspaceIdAndStatusOrderByDueDateAsc(Long workspaceId, TaskStatus status);
}
