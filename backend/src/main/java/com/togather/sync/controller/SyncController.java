package com.togather.sync.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.response.ApiResponse;
import com.togather.sync.dto.SyncStatusResponse;
import com.togather.sync.service.SyncService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 동기화 상태 조회와 즉시 동기화 (B-03). 예: POST /api/workspaces/1/sync/SLACK */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/sync/{provider}")
public class SyncController {

    private final SyncService syncService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    @GetMapping
    public ApiResponse<List<SyncStatusResponse>> getStatuses(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                             @PathVariable Provider provider) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(syncService.getStatuses(workspaceId, provider));
    }

    /** force=true: 동기화 버튼을 직접 누른 경우. 1분 간격 제한 없이 바로 동기화한다. */
    @PostMapping
    public ApiResponse<List<SyncStatusResponse>> syncNow(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                         @PathVariable Provider provider,
                                                         @RequestParam(defaultValue = "false") boolean force) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(syncService.syncNow(workspaceId, provider, userId, force));
    }
}
