package com.togather.content.drive.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.auth.WorkspaceChangeGuard;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.domain.DriveOwnershipJob;
import com.togather.content.drive.domain.DriveOwnershipJob.Status;
import com.togather.content.drive.domain.DriveWorkspaceSetting;
import com.togather.content.drive.repository.DriveOwnershipJobRepository;
import com.togather.content.drive.repository.DriveWorkspaceSettingRepository;
import com.togather.integration.event.ServiceConnectedEvent;
import com.togather.integration.service.IntegrationService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 팀장·연동이 바뀔 때 Drive 소유권을 지킨다.
 *
 * <ul>
 *   <li>팀장 넘기기: 새 팀장이 Drive를 연동했어야 하고, 넘긴 뒤 이전 팀장 소유 항목을 새 팀장에게 넘기는 작업을 만든다.</li>
 *   <li>인계 중에는 두 사람을 내보내거나 Drive 연결을 해제할 수 없다.</li>
 *   <li>팀장이 Drive를 연동하면 Drive 소유자로 정하고 휴지통 폴더를 만든다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriveOwnershipGuard implements WorkspaceChangeGuard {

    private static final Provider PROVIDER = Provider.GOOGLE_DRIVE;

    private final DriveWorkspaceSettingRepository settingRepository;
    private final DriveOwnershipJobRepository jobRepository;
    private final IntegrationService integrationService;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final DriveOwnerAccess ownerAccess;
    private final DriveTrashService trashService;

    @Override
    public void beforeOwnerTransfer(Long workspaceId, Long fromUserId, Long toUserId) {
        if (runningJob(workspaceId).isPresent()) {
            throw new BusinessException(ErrorCode.DRIVE_OWNERSHIP_TRANSFER_IN_PROGRESS);
        }
        if (usesDrive(workspaceId) && !integrationService.hasConnection(workspaceId, toUserId, PROVIDER)) {
            throw new BusinessException(ErrorCode.DRIVE_NEW_OWNER_NOT_CONNECTED);
        }
    }

    @Override
    public void afterOwnerTransfer(Long workspaceId, Long fromUserId, Long toUserId) {
        DriveWorkspaceSetting setting = settingRepository.findById(workspaceId)
            .orElseGet(() -> new DriveWorkspaceSetting(workspaceId, fromUserId));
        Long currentOwner = setting.getOwnerUserId() != null ? setting.getOwnerUserId() : fromUserId;
        if (!usesDrive(workspaceId) || !integrationService.hasConnection(workspaceId, currentOwner, PROVIDER)) {
            // 이전 소유자 소유의 항목이 없거나 넘길 수 없으니 바로 새 팀장을 소유자로 둔다.
            setting.changeOwner(toUserId);
            settingRepository.save(setting);
            return;
        }
        setting.changeOwner(currentOwner);
        settingRepository.save(setting);
        jobRepository.save(new DriveOwnershipJob(workspaceId, currentOwner, toUserId));
        log.info("Drive 소유권 인계 시작: workspaceId={}, from={}, to={}", workspaceId, currentOwner, toUserId);
    }

    @Override
    public void beforeMemberRemove(Long workspaceId, Long userId) {
        if (runningJob(workspaceId).filter(job -> job.involves(userId)).isPresent()) {
            throw new BusinessException(ErrorCode.DRIVE_OWNERSHIP_TRANSFER_IN_PROGRESS);
        }
    }

    @Override
    public void beforeDisconnect(Long workspaceId, Long userId, Provider provider) {
        if (provider != PROVIDER) {
            return;
        }
        // 인계에는 두 사람의 토큰이 모두 필요하다.
        if (runningJob(workspaceId).filter(job -> job.involves(userId)).isPresent()) {
            throw new BusinessException(ErrorCode.DRIVE_OWNERSHIP_TRANSFER_IN_PROGRESS);
        }
    }

    /** 팀장이 Drive를 연동(재인증 포함)하면 Drive 소유자로 정하고 휴지통 폴더를 준비한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConnected(ServiceConnectedEvent event) {
        if (event.provider() != PROVIDER) {
            return;
        }
        Long workspaceId = event.workspaceId();
        try {
            if (!workspaceAccessChecker.findMainOwner(workspaceId).map(event.userId()::equals).orElse(false)) {
                return;
            }
            DriveWorkspaceSetting setting = settingRepository.findById(workspaceId)
                .orElseGet(() -> new DriveWorkspaceSetting(workspaceId, event.userId()));
            if (setting.getOwnerUserId() == null) {
                setting.changeOwner(event.userId());
            }
            settingRepository.save(setting);
            if (!event.userId().equals(setting.getOwnerUserId())) {
                return; // 인계 중이면 이전 팀장이 아직 소유자다.
            }
            trashService.ensureTrashFolder(workspaceId, ownerAccess.requireOwnerToken(workspaceId));
            ownerAccess.resetSyncCursors(workspaceId);
        } catch (RuntimeException e) {
            log.warn("팀장 Drive 연동 후 준비 실패(휴지통 폴더는 처음 쓸 때 다시 만든다): workspaceId={}, error={}",
                workspaceId, e.getMessage());
        }
    }

    private Optional<DriveOwnershipJob> runningJob(Long workspaceId) {
        return jobRepository.findFirstByWorkspaceIdAndStatus(workspaceId, Status.RUNNING);
    }

    private boolean usesDrive(Long workspaceId) {
        return !integrationService.getConnectionTargets(workspaceId, PROVIDER).isEmpty();
    }
}
