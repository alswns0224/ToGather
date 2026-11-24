package com.togather.workspace.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.workspace.domain.WorkspaceMember;
import com.togather.workspace.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 권한 검사는 조회만 하므로, 권한이 없다는 예외(BusinessException)가 호출한 쪽의 트랜잭션을 롤백 전용으로 만들면 안 된다.
 * 그러면 호출한 쪽이 예외를 처리해도 커밋할 때 UnexpectedRollbackException(500)으로 바뀐다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = BusinessException.class)
public class WorkspaceAccessCheckerImpl implements WorkspaceAccessChecker {

    private final WorkspaceMemberRepository workspaceMemberRepository;

    @Override
    public void checkMember(Long workspaceId, Long userId) {
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
    }

    @Override
    public void checkOwner(Long workspaceId, Long userId) {
        WorkspaceMember member = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));
        if (!member.isAdmin()) {
            throw new BusinessException(ErrorCode.WORKSPACE_OWNER_REQUIRED);
        }
    }

    @Override
    public void checkMainOwner(Long workspaceId, Long userId) {
        WorkspaceMember member = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));
        if (!member.isOwner()) {
            throw new BusinessException(ErrorCode.MAIN_OWNER_REQUIRED);
        }
    }
}
