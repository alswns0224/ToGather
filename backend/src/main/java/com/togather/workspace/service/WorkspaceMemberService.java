package com.togather.workspace.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.WorkspaceRole;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.workspace.domain.WorkspaceMember;
import com.togather.workspace.dto.MemberResponse;
import com.togather.workspace.repository.WorkspaceMemberRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-01-09. 팀원 목록·내보내기 (UC-02-07 ~ 08)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkspaceMemberService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final UserService userService;
    private final InviteLinkService inviteLinkService;

    /** 팀원 목록. 팀장, 관리자, 팀원 순, 같은 역할은 참여 순서 */
    public List<MemberResponse> getMembers(Long workspaceId) {
        List<WorkspaceMember> members = workspaceMemberRepository.findAllByWorkspaceId(workspaceId);
        Map<Long, UserSummary> users = userService.getUsers(members.stream().map(WorkspaceMember::getUserId).toList())
                .stream().collect(Collectors.toMap(UserSummary::userId, Function.identity()));
        return classifyByRole(members).stream()
                .filter(m -> users.containsKey(m.getUserId()))
                .map(m -> MemberResponse.of(users.get(m.getUserId()), m.getRole(), m.getCreatedAt()))
                .toList();
    }

    /**
     * 관리자 임명(ADMIN) · 해제(MEMBER). 팀장만 할 수 있고, 팀장 자신의 역할은 바꿀 수 없다(넘기기를 쓴다).
     * 관리자는 자기 자신을 해제(내려놓기)하는 것만 할 수 있다.
     */
    @Transactional
    public List<MemberResponse> changeRole(Long workspaceId, Long userId, Long targetUserId, WorkspaceRole role) {
        if (role == WorkspaceRole.OWNER) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        WorkspaceMember target = findMember(workspaceId, targetUserId);
        boolean stepDown = userId.equals(targetUserId) && target.getRole() == WorkspaceRole.ADMIN
                && role == WorkspaceRole.MEMBER;
        if (!stepDown) {
            workspaceAccessChecker.checkMainOwner(workspaceId, userId);
        }
        if (target.isOwner()) {
            throw new BusinessException(ErrorCode.CANNOT_CHANGE_OWNER_ROLE);
        }
        target.changeRole(role);
        workspaceMemberRepository.flush();
        return getMembers(workspaceId);
    }

    /** 팀장 확인 후 대상 팀원에게 팀장을 넘기고, 넘긴 사람은 팀원이 된다. */
    @Transactional
    public List<MemberResponse> transferOwner(Long workspaceId, Long userId, Long targetUserId) {
        workspaceAccessChecker.checkMainOwner(workspaceId, userId);
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.CANNOT_TRANSFER_TO_SELF);
        }
        findMember(workspaceId, targetUserId).changeRole(WorkspaceRole.OWNER);
        findMember(workspaceId, userId).changeRole(WorkspaceRole.MEMBER);
        workspaceMemberRepository.flush();
        return getMembers(workspaceId);
    }

    private WorkspaceMember findMember(Long workspaceId, Long userId) {
        return workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    /**
     * 팀장·관리자 확인 후 일반 팀원을 내보낸다. 팀장·관리자는 내보낼 수 없다.
     * 내보낸 사람이 다시 못 들어오게 초대 링크를 새로 만든다.
     */
    @Transactional
    public List<MemberResponse> removeMember(Long workspaceId, Long userId, Long targetUserId) {
        workspaceAccessChecker.checkOwner(workspaceId, userId);
        WorkspaceMember target = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (target.isAdmin()) {
            throw new BusinessException(ErrorCode.CANNOT_REMOVE_OWNER);
        }
        workspaceMemberRepository.delete(target);
        workspaceMemberRepository.flush();
        inviteLinkService.renewInviteLinks(workspaceId);
        return getMembers(workspaceId);
    }

    /** 다른 파트 제공용: 워크스페이스 팀원의 사용자 ID 목록 (docs/shared-interfaces.md) */
    public List<Long> getMemberUserIds(Long workspaceId) {
        return workspaceMemberRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(WorkspaceMember::getUserId)
                .toList();
    }

    public boolean isMember(Long workspaceId, Long userId) {
        return workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId);
    }

    private List<WorkspaceMember> classifyByRole(List<WorkspaceMember> members) {
        return members.stream()
                .sorted(Comparator.comparing((WorkspaceMember m) -> m.getRole().ordinal())
                        .thenComparing(WorkspaceMember::getCreatedAt))
                .toList();
    }
}
