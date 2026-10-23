package com.togather.task.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.common.response.ApiResponse;
import com.togather.content.service.ContentRefService;
import com.togather.task.dto.TaskContext;
import com.togather.task.dto.TaskSummary;
import com.togather.task.service.TaskContextService;
import com.togather.task.service.TaskService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}")
public class TaskContextController {

    private final TaskContextService taskContextService;
    private final TaskService taskService;
    private final ContentRefService contentRefService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    /** Task에 연결된 대화·문서·파일·결정사항·회의를 통합 조회 */
    @GetMapping("/tasks/{taskId}/context")
    public ApiResponse<TaskContext> getTaskContext(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long taskId
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);

        validateTaskWorkspace(workspaceId, taskId);

        return ApiResponse.ok(
            taskContextService.getTaskContext(taskId)
        );
    }

    /** 특정 콘텐츠와 연결된 Task 목록 */
    @GetMapping("/contents/{contentRefId}/tasks")
    public ApiResponse<List<TaskSummary>> getTasksByContent(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long contentRefId
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);

        validateContentWorkspace(workspaceId, contentRefId);

        List<TaskSummary> tasks =
            taskContextService
                .getTasksByContents(List.of(contentRefId))
                .getOrDefault(contentRefId, List.of());

        return ApiResponse.ok(tasks);
    }

    private void validateTaskWorkspace(
        Long workspaceId,
        Long taskId
    ) {
        TaskSummary task = taskService.getTask(taskId);

        if (!workspaceId.equals(task.workspaceId())) {
            throw new BusinessException(
                ErrorCode.TASK_NOT_FOUND
            );
        }
    }

    private void validateContentWorkspace(
        Long workspaceId,
        Long contentRefId
    ) {
        ContentRefService.ContentOwner owner =
            contentRefService.getOwner(contentRefId);

        if (!workspaceId.equals(owner.workspaceId())) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }
    }
}
