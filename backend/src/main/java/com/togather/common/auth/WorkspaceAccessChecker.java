package com.togather.common.auth;

/**
 * 워크스페이스 권한 검사. 실패 시 BusinessException(WORKSPACE_ACCESS_DENIED / WORKSPACE_OWNER_REQUIRED)을 던진다.
 * 구현: workspace.service.WorkspaceAccessCheckerImpl (A 담당)
 */
public interface WorkspaceAccessChecker {

    void checkMember(Long workspaceId, Long userId);

    void checkOwner(Long workspaceId, Long userId);
}
