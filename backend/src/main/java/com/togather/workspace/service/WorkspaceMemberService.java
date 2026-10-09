package com.togather.workspace.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.auth.WorkspaceAccessChecker;
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

    /** 팀원 목록. 팀장(OWNER)이 먼저, 그다음 참여 순서 */
    public List<MemberResponse> getMembers(Long workspaceId) {
        List<WorkspaceMember> members = workspaceMemberRepository.findAllByWorkspaceId(workspaceId);
        Map<Long, UserSummary> users = userService.getUsers(members.stream().map(WorkspaceMember::getUserId).toList())
                .stream().collect(Collectors.toMap(UserSummary::userId, Function.identity()));
        return classifyByRole(members).stream()
                .filter(m -> users.containsKey(m.getUserId()))
                .map(m -> MemberResponse.of(users.get(m.getUserId()), m.getRole(), m.getCreatedAt()))
                .toList();
    }

    /** OWNER 확인 후 대상 팀원을 내보낸다. 팀장은 내보낼 수 없다. */
    @Transactional
    public List<MemberResponse> removeMember(Long workspaceId, Long userId, Long targetUserId) {
        workspaceAccessChecker.checkOwner(workspaceId, userId);
        WorkspaceMember target = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (target.isOwner()) {
            throw new BusinessException(ErrorCode.CANNOT_REMOVE_OWNER);
        }
        workspaceMemberRepository.delete(target);
        workspaceMemberRepository.flush();
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
                .sorted(Comparator.comparing((WorkspaceMember m) -> !m.isOwner())
                        .thenComparing(WorkspaceMember::getCreatedAt))
                .toList();
    }
}
