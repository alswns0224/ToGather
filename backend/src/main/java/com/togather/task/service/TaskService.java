package com.togather.task.service;

import com.togather.common.enums.TaskStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.task.domain.Task;
import com.togather.task.dto.TaskCommand;
import com.togather.task.dto.TaskSummary;
import com.togather.task.repository.TaskRepository;
import com.togather.workspace.service.WorkspaceMemberService;
import com.togather.task.dto.TaskDetailResponse;
import com.togather.common.response.PageResponse;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskService {

    private final TaskRepository taskRepository;
    private final WorkspaceMemberService workspaceMemberService;

    /** Task 생성 - 다른 파트에서도 사용하는 공유 인터페이스 */
    @Transactional
    public Long createTask(Long workspaceId, TaskCommand command) {

        if (command.assigneeId() != null
            && !workspaceMemberService.isMember(workspaceId, command.assigneeId())) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }

        Task task = new Task(
            workspaceId,
            command.title(),
            command.description(),
            command.dueDate(),
            command.status()
        );

        if (command.assigneeId() != null) {
            task.assignTo(command.assigneeId());
        }

        if (command.originAnalysisItemId() != null) {
            task.linkOriginAnalysisItem(command.originAnalysisItemId());
        }

        if (command.deliverables() != null) {
            command.deliverables().forEach(
                deliverable -> task.addDeliverable(
                    deliverable.name(),
                    deliverable.ext()
                )
            );
        }

        return taskRepository.save(task).getTaskId();
    }

    /** 워크스페이스 전체 Task 목록 */
    public List<TaskSummary> getTasksByWorkspace(Long workspaceId) {
        LocalDate today = LocalDate.now();

        return taskRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
            .stream()
            .map(task -> TaskSummary.from(task, today))
            .toList();
    }

    /** 내가 담당하는 Task 목록 - 다른 파트에서도 사용하는 공유 인터페이스 */
    public List<TaskSummary> getMyTasks(Long userId) {
        LocalDate today = LocalDate.now();

        return taskRepository.findAllByAssigneeIdOrderByDueDateAsc(userId)
            .stream()
            .map(task -> TaskSummary.from(task, today))
            .toList();
    }

    /** Task 단건 조회 */
    public TaskSummary getTask(Long taskId) {
        return taskRepository.findById(taskId)
            .map(task -> TaskSummary.from(task, LocalDate.now()))
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    /** 여러 Task 조회 - 기존 A 파트 사용 가능성이 있으므로 유지 */
    public List<TaskSummary> getTasks(Collection<Long> taskIds) {
        LocalDate today = LocalDate.now();

        return taskRepository.findAllById(taskIds)
            .stream()
            .map(task -> TaskSummary.from(task, today))
            .toList();
    }

    /** 진행 중 Task - 회의 안건 후보용 */
    public List<TaskSummary> getTasksInProgress(Long workspaceId) {
        LocalDate today = LocalDate.now();

        return taskRepository
            .findAllByWorkspaceIdAndStatusOrderByDueDateAsc(
                workspaceId,
                TaskStatus.IN_PROGRESS
            )
            .stream()
            .map(task -> TaskSummary.from(task, today))
            .toList();
    }

    /** Task 수정 */
    @Transactional
    public TaskSummary updateTask(
        Long workspaceId,
        Long taskId,
        String title,
        String description,
        Long assigneeId,
        LocalDate dueDate,
        TaskStatus status
    ) {
        Task task = taskRepository.findById(taskId)
            .filter(t -> t.getWorkspaceId().equals(workspaceId))
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));

        if (assigneeId != null
            && !workspaceMemberService.isMember(workspaceId, assigneeId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }

        task.update(
            title != null ? title : task.getTitle(),
            description != null ? description : task.getDescription(),
            dueDate != null ? dueDate : task.getDueDate()
        );

        if (assigneeId != null) {
            task.assignTo(assigneeId);
        }

        if (status != null) {
            task.changeStatus(status);
        }

        return TaskSummary.from(task, LocalDate.now());
    }

    /** Task 삭제 */
    @Transactional
    public void deleteTask(Long workspaceId, Long taskId) {
        Task task = taskRepository.findById(taskId)
            .filter(t -> t.getWorkspaceId().equals(workspaceId))
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));

        taskRepository.delete(task);
    }

    /** 화면용 Task 상세 조회 */
    public TaskDetailResponse getTaskDetail(Long workspaceId, Long taskId) {
        Task task = taskRepository.findById(taskId)
            .filter(t -> t.getWorkspaceId().equals(workspaceId))
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));

        return TaskDetailResponse.from(task, LocalDate.now());
    }

    /** 화면용: 워크스페이스 Task 페이지 조회 */
    public PageResponse<TaskSummary> getTaskPage(
        Long workspaceId,
        Long userId,
        boolean mine,
        int page,
        int size
    ) {
        LocalDate today = LocalDate.now();
        PageRequest pageable = PageRequest.of(page, size);

        if (mine) {
            return PageResponse.from(
                taskRepository.findAllByWorkspaceIdAndAssigneeIdOrderByCreatedAtDesc(
                    workspaceId,
                    userId,
                    pageable
                ),
                task -> TaskSummary.from(task, today)
            );
        }

        return PageResponse.from(
            taskRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(
                workspaceId,
                pageable
            ),
            task -> TaskSummary.from(task, today)
        );
    }
}
