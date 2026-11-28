package com.togather.task.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.TaskStatus;
import com.togather.common.response.ApiResponse;
import com.togather.common.response.PageResponse;
import com.togather.meeting.dto.MeetingSummary;
import com.togather.task.dto.TaskCreateRequest;
import com.togather.task.dto.TaskDetailResponse;
import com.togather.task.dto.TaskFilter;
import com.togather.task.dto.TaskProgressResponse;
import com.togather.task.dto.TaskSort;
import com.togather.task.dto.TaskSummary;
import com.togather.task.dto.TaskUpdateRequest;
import com.togather.task.service.TaskService;
import com.togather.task.service.TaskReadService;
import com.togather.task.service.TaskDeletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final TaskReadService taskReadService;
    private final TaskDeletionService taskDeletionService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    /** 워크스페이스 Task 목록 */
    @GetMapping
    public ApiResponse<PageResponse<TaskSummary>> getTasks(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @RequestParam(required = false, defaultValue = "false") boolean mine,
        @RequestParam(required = false, defaultValue = "0")
        @Min(0) int page,
        @RequestParam(required = false, defaultValue = "20")
        @Min(1) @Max(100) int size,
        @RequestParam(required = false) @Positive Long assigneeId,
        @RequestParam(defaultValue = "false") boolean unassigned,
        @RequestParam(required = false) TaskStatus status,
        @RequestParam(required = false) @Size(max = 200) String query,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore,
        @RequestParam(defaultValue = "RECENT") TaskSort sort
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);

        return ApiResponse.ok(
            taskReadService.getPage(workspaceId, userId, mine,
                    new TaskFilter(assigneeId, unassigned, status, query, dueBefore, sort), page, size)
        );
    }

    /** 페이지와 무관하게 같은 검색 조건의 전체·담당자별 진행 현황을 집계한다. */
    @GetMapping("/progress")
    public ApiResponse<TaskProgressResponse> getProgress(
        @LoginUser Long userId, @PathVariable Long workspaceId,
        @RequestParam(defaultValue = "false") boolean mine,
        @RequestParam(required = false) @Positive Long assigneeId,
        @RequestParam(defaultValue = "false") boolean unassigned,
        @RequestParam(required = false) TaskStatus status,
        @RequestParam(required = false) @Size(max = 200) String query,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueBefore
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(taskReadService.getProgress(workspaceId, userId, mine,
                new TaskFilter(assigneeId, unassigned, status, query, dueBefore, TaskSort.RECENT)));
    }

    /** Task 상세 조회 */
    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailResponse> getTask(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long taskId
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);

        return ApiResponse.ok(
            taskService.getTaskDetail(workspaceId, taskId)
        );
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
                request.dueTime(),
                request.status()
            )
        );
    }

    /** 삭제를 막는 회의 목록. 화면에서 안건 연결을 해제할 회의로 안내한다. */
    @GetMapping("/{taskId}/deletion-blockers")
    public ApiResponse<List<MeetingSummary>> getDeletionBlockers(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long taskId
    ) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(taskDeletionService.getDeletionBlockers(workspaceId, taskId));
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
        taskDeletionService.deleteTask(workspaceId, taskId);
    }
}
