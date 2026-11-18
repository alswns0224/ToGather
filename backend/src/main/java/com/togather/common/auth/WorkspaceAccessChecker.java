package com.togather.common.auth;

/**
 * 워크스페이스 권한 검사. 실패 시 BusinessException(WORKSPACE_ACCESS_DENIED / WORKSPACE_OWNER_REQUIRED)을 던진다.
 * 구현: workspace.service.WorkspaceAccessCheckerImpl (A 담당)
 */
public interface WorkspaceAccessChecker {

    void checkMember(Long workspaceId, Long userId);

    /** 팀장 또는 관리자: 초대 링크 관리, 일반 팀원 내보내기 등 */
    void checkOwner(Long workspaceId, Long userId);

    /** 팀장만: 관리자 임명·해제, 팀장 넘기기, 워크스페이스 수정·삭제 */
    void checkMainOwner(Long workspaceId, Long userId);
}
