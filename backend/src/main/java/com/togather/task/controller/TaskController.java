package com.togather.task.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.common.response.ApiResponse;
import com.togather.task.dto.TaskCreateRequest;
import com.togather.task.dto.TaskSummary;
import com.togather.task.dto.TaskUpdateRequest;
import com.togather.task.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/tasks")
public class TaskController {

    private final TaskService taskService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    /** 워크스페이스 Task 목록 */
    @GetMapping
    public ApiResponse<List<TaskSummary>> getTasks(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @RequestParam(required = false, defaultValue = "false") boolean mine
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);

        List<TaskSummary> tasks = mine
            ? taskService.getMyTasks(userId)
            : taskService.getTasksByWorkspace(workspaceId);

        return ApiResponse.ok(tasks);
    }

    /** Task 상세 조회 */
    @GetMapping("/{taskId}")
    public ApiResponse<TaskSummary> getTask(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long taskId
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);

        TaskSummary task = taskService.getTask(taskId);

        if (!workspaceId.equals(task.workspaceId())) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }

        return ApiResponse.ok(task);
    }

    /** Task 생성 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskSummary> createTask(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @Valid @RequestBody TaskCreateRequest request
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);

        Long taskId = taskService.createTask(
            workspaceId,
            request.toCommand()
        );

        return ApiResponse.ok(taskService.getTask(taskId));
    }

    /** Task 수정 */
    @PatchMapping("/{taskId}")
    public ApiResponse<TaskSummary> updateTask(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long taskId,
        @Valid @RequestBody TaskUpdateRequest request
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);

        return ApiResponse.ok(
            taskService.updateTask(
                workspaceId,
                taskId,
                request.title(),
                request.description(),
                request.assigneeId(),
                request.dueDate(),
                request.status()
            )
        );
    }

    /** Task 삭제 */
    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long taskId
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        taskService.deleteTask(workspaceId, taskId);
    }
}
