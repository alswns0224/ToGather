package com.togather.workspace.service;

import com.togather.auth.service.OAuthStateStore;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.config.TogatherProperties;
import com.togather.common.enums.WorkspaceRole;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.workspace.domain.InviteLink;
import com.togather.workspace.domain.Workspace;
import com.togather.workspace.domain.WorkspaceMember;
import com.togather.workspace.dto.InviteInfoResponse;
import com.togather.workspace.dto.InviteLinkResponse;
import com.togather.workspace.repository.InviteLinkRepository;
import com.togather.workspace.repository.WorkspaceMemberRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-01-08. 초대 링크 생성·참여 (UC-02-05 ~ 06)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InviteLinkService {

    /** 초대 링크 유효 기간 */
    static final Duration LINK_TTL = Duration.ofDays(7);

    private final InviteLinkRepository inviteLinkRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final TogatherProperties properties;

    /** OWNER 확인 후 새 초대 링크를 만든다. 이전 링크도 만료 전까지는 유효하다. */
    @Transactional
    public InviteLinkResponse createInviteLink(Long workspaceId, Long userId) {
        workspaceAccessChecker.checkOwner(workspaceId, userId);
        InviteLink link = inviteLinkRepository.save(
                new InviteLink(OAuthStateStore.randomToken(), workspaceId, LocalDateTime.now().plus(LINK_TTL)));
        return InviteLinkResponse.of(link, properties.appUrl());
    }

    /** OWNER 확인 후 아직 유효한 링크 중 가장 늦게 만료되는 것. 없으면 null */
    public InviteLinkResponse getActiveInviteLink(Long workspaceId, Long userId) {
        workspaceAccessChecker.checkOwner(workspaceId, userId);
        return inviteLinkRepository.findFirstByWorkspaceIdAndExpiresAtAfterOrderByExpiresAtDesc(workspaceId, LocalDateTime.now())
                .map(link -> InviteLinkResponse.of(link, properties.appUrl()))
                .orElse(null);
    }

    /** 초대 링크 유효성과 참여 여부를 확인한다. */
    public InviteInfoResponse getInviteInfo(String token, Long userId) {
        InviteLink link = findValidLink(token);
        Workspace workspace = workspaceService.findWorkspace(link.getWorkspaceId());
        return new InviteInfoResponse(
                workspace.getWorkspaceId(),
                workspace.getName(),
                workspace.getDescription(),
                workspaceMemberRepository.countByWorkspaceId(workspace.getWorkspaceId()),
                workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspace.getWorkspaceId(), userId));
    }

    /** 링크 유효성 확인 후 사용자를 MEMBER로 등록하고 워크스페이스 ID를 반환한다. 이미 멤버면 그대로 반환한다. */
    @Transactional
    public Long joinByInviteLink(String token, Long userId) {
        InviteLink link = findValidLink(token);
        Long workspaceId = link.getWorkspaceId();
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            workspaceMemberRepository.save(new WorkspaceMember(workspaceId, userId, WorkspaceRole.MEMBER));
        }
        return workspaceId;
    }

    private InviteLink findValidLink(String token) {
        InviteLink link = inviteLinkRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_LINK_INVALID));
        if (link.isExpired(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.INVITE_LINK_EXPIRED);
        }
        return link;
    }
}
