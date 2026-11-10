package com.togather.task.repository;

import com.togather.common.enums.TaskStatus;
import com.togather.task.domain.Task;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface TaskRepository extends JpaRepository<Task, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Task> findLockedByTaskIdAndWorkspaceId(Long taskId, Long workspaceId);

    /** 기존 공유 기능용 - 유지 */
    List<Task> findAllByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    /** 기존 공유 기능용 - 유지 */
    List<Task> findAllByAssigneeIdOrderByDueDateAsc(Long assigneeId);

    /** 회의 안건 후보용 - 유지 */
    List<Task> findAllByWorkspaceIdAndStatusOrderByDueDateAsc(
        Long workspaceId,
        TaskStatus status
    );

    /** 화면용: 워크스페이스 전체 Task 페이지 조회 */
    Page<Task> findAllByWorkspaceIdOrderByCreatedAtDesc(
        Long workspaceId,
        Pageable pageable
    );

    /** 화면용: 현재 워크스페이스에서 내가 담당하는 Task 페이지 조회 */
    Page<Task> findAllByWorkspaceIdAndAssigneeIdOrderByCreatedAtDesc(
        Long workspaceId,
        Long assigneeId,
        Pageable pageable
    );
}
