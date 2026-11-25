package com.togather.content.drive.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.domain.DriveWorkspaceSetting;
import com.togather.content.drive.repository.DriveWorkspaceSettingRepository;
import com.togather.content.service.TeamTokenResolver;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.OwnAccess;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팀 Drive의 모든 폴더·파일은 팀장(Drive 소유자) 한 명이 소유한다. 업로드·휴지통·동기화는 그 사람의 토큰으로 한다.
 *
 * <p>Drive 소유자는 보통 팀장이고, 팀장을 넘기는 동안에는 소유권 인계가 끝날 때까지 이전 팀장이다.
 * 팀장이 아직 Drive를 연동하지 않았으면 조회·업로드는 이전 방식(폴더를 고른 사람의 토큰)으로 하고,
 * 휴지통처럼 소유자만 할 수 있는 작업은 막는다.
 */
@Component
@RequiredArgsConstructor
public class DriveOwnerAccess {

    private static final Provider PROVIDER = Provider.GOOGLE_DRIVE;

    private final DriveWorkspaceSettingRepository settingRepository;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final IntegrationService integrationService;
    private final TeamTokenResolver teamTokenResolver;
    private final JdbcTemplate jdbc;

    /** Drive 소유자. 설정이 없으면 지금 팀장 */
    public Optional<Long> ownerUserId(Long workspaceId) {
        return settingRepository.findById(workspaceId)
            .map(DriveWorkspaceSetting::getOwnerUserId)
            .or(() -> workspaceAccessChecker.findMainOwner(workspaceId));
    }

    public Optional<String> trashFolderId(Long workspaceId) {
        return settingRepository.findById(workspaceId).map(DriveWorkspaceSetting::getTrashFolderId);
    }

    /** 소유자 연결이 있으면 그 토큰. 재인증이 필요하면 DRIVE_OWNER_REAUTH_REQUIRED */
    public Optional<OwnerToken> findOwnerToken(Long workspaceId) {
        Optional<Long> owner = ownerUserId(workspaceId);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        try {
            return integrationService.findOwnAccess(workspaceId, owner.get(), PROVIDER)
                .map(access -> new OwnerToken(owner.get(), access.externalAccountId(), access.accessToken()));
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.REAUTH_REQUIRED) {
                throw new BusinessException(ErrorCode.DRIVE_OWNER_REAUTH_REQUIRED);
            }
            throw e;
        }
    }

    /** 소유자만 할 수 있는 작업(휴지통·영구 삭제·폴더 생성)용 */
    public OwnerToken requireOwnerToken(Long workspaceId) {
        return findOwnerToken(workspaceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.DRIVE_OWNER_NOT_CONNECTED));
    }

    /** 조회·업로드·동기화용: 소유자 토큰, 소유자가 아직 연동하지 않았으면 폴더를 고른 사람의 토큰 */
    public String operationToken(Long workspaceId, String folderId, Long requestedBy) {
        return findOwnerToken(workspaceId)
            .map(OwnerToken::accessToken)
            .orElseGet(() -> teamTokenResolver.tokenFor(workspaceId, PROVIDER, folderId, requestedBy));
    }

    /** 팀원의 Drive 연결 (소유권을 팀장에게 넘길 때 원래 소유자 토큰으로 요청해야 한다) */
    public Optional<OwnAccess> memberAccess(Long workspaceId, Long userId) {
        try {
            return integrationService.findOwnAccess(workspaceId, userId, PROVIDER);
        } catch (BusinessException e) {
            return Optional.empty();
        }
    }

    public Optional<String> accountEmail(Long workspaceId, Long userId) {
        return integrationService.findOwnExternalAccountId(workspaceId, userId, PROVIDER);
    }

    /** 토큰 계정이 바뀌면 이전 계정의 변경 커서는 쓸 수 없으므로, 다음 동기화를 전체 동기화로 시작한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetSyncCursors(Long workspaceId) {
        jdbc.update("UPDATE sync_state SET sync_cursor = NULL WHERE workspace_id = ? AND provider = ?",
            workspaceId, PROVIDER.name());
    }

    public record OwnerToken(Long userId, String email, String accessToken) {
        @Override
        public String toString() {
            return "OwnerToken[userId=" + userId + "]";
        }
    }
}
