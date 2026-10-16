package com.togather.task.repository;

import com.togather.common.enums.TaskStatus;
import com.togather.task.domain.Task;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /** 워크스페이스 전체 Task 목록 */
    List<Task> findAllByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    /** 특정 사용자가 담당하는 Task 목록 */
    List<Task> findAllByAssigneeIdOrderByDueDateAsc(Long assigneeId);

    /** 진행 중인 Task 목록 - 회의 안건 후보용 */
    List<Task> findAllByWorkspaceIdAndStatusOrderByDueDateAsc(
        Long workspaceId,
        TaskStatus status
    );
}
