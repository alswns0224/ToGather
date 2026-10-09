package com.togather.workspace.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.response.ApiResponse;
import com.togather.workspace.dto.WorkspaceRequest;
import com.togather.workspace.dto.WorkspaceResponse;
import com.togather.workspace.service.WorkspaceService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** C-01-02 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public ApiResponse<List<WorkspaceResponse>> getWorkspaces(@LoginUser Long userId) {
        return ApiResponse.ok(workspaceService.getWorkspaces(userId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceResponse> createWorkspace(@LoginUser Long userId,
                                                          @Valid @RequestBody WorkspaceRequest request) {
        return ApiResponse.ok(workspaceService.createWorkspace(userId, request));
    }

    @GetMapping("/{workspaceId}")
    public ApiResponse<WorkspaceResponse> getWorkspace(@LoginUser Long userId, @PathVariable Long workspaceId) {
        return ApiResponse.ok(workspaceService.getWorkspace(workspaceId, userId));
    }

    @PatchMapping("/{workspaceId}")
    public ApiResponse<WorkspaceResponse> updateWorkspace(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                          @Valid @RequestBody WorkspaceRequest request) {
        return ApiResponse.ok(workspaceService.updateWorkspace(workspaceId, userId, request));
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkspace(@LoginUser Long userId, @PathVariable Long workspaceId) {
        workspaceService.deleteWorkspace(workspaceId, userId);
    }
}
