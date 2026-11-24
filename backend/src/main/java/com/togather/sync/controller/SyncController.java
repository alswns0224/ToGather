package com.togather.sync.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.response.ApiResponse;
import com.togather.integration.service.IntegrationService;
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
    private final IntegrationService integrationService;

    @GetMapping
    public ApiResponse<List<SyncStatusResponse>> getStatuses(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                             @PathVariable Provider provider) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(currentOnly(workspaceId, provider, syncService.getStatuses(workspaceId, provider)));
    }

    /**
     * 팀 자료 서비스는 지금 연동 대상인 채널·페이지·폴더의 상태만 보여 준다.
     * (예전에 골랐다가 해제한 대상의 실패 기록 때문에 "최근 실패"가 계속 뜨지 않게)
     */
    private List<SyncStatusResponse> currentOnly(Long workspaceId, Provider provider, List<SyncStatusResponse> statuses) {
        if (IntegrationService.PERSONAL_PROVIDERS.contains(provider)) {
            return statuses;
        }
        java.util.Set<String> targets = java.util.Set.copyOf(integrationService.getConnectionTargets(workspaceId, provider));
        return statuses.stream().filter(s -> targets.contains(s.targetId())).toList();
    }

    /** force=true: 동기화 버튼을 직접 누른 경우. 1분 간격 제한 없이 바로 동기화한다. */
    @PostMapping
    public ApiResponse<List<SyncStatusResponse>> syncNow(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                         @PathVariable Provider provider,
                                                         @RequestParam(defaultValue = "false") boolean force) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(currentOnly(workspaceId, provider, syncService.syncNow(workspaceId, provider, userId, force)));
    }
}
