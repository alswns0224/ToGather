package com.togather.workspace.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.workspace.domain.WorkspaceMember;
import com.togather.workspace.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
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
        if (!member.isOwner()) {
            throw new BusinessException(ErrorCode.WORKSPACE_OWNER_REQUIRED);
        }
    }
}
