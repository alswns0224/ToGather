package com.togather.content.drive.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveItem;
import com.togather.content.drive.domain.DriveOwnershipJob;
import com.togather.content.drive.domain.DriveOwnershipJob.Status;
import com.togather.content.drive.domain.DriveWorkspaceSetting;
import com.togather.content.drive.dto.DriveOwnershipStatus;
import com.togather.content.drive.repository.DriveOwnershipJobRepository;
import com.togather.content.drive.repository.DriveWorkspaceSettingRepository;
import com.togather.content.drive.service.DriveOwnerAccess.OwnerToken;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.OwnAccess;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Drive 항목의 소유자를 팀장 한 명으로 유지한다.
 *
 * <ul>
 *   <li>정리: 팀원이 Drive 웹에서 공유 폴더에 직접 올린 항목처럼 팀장 소유가 아닌 것을 팀장에게 넘긴다.
 *       원래 소유자가 ToGather에 Drive를 연동했어야 넘길 수 있다.</li>
 *   <li>인계: 팀장을 넘기면 이전 팀장 소유 항목을 새 팀장에게 넘기고, 다 넘기면 Drive 소유자를 새 팀장으로 바꾼다.</li>
 * </ul>
 * 개인 Google 계정끼리는 현재 소유자가 이전을 요청(pendingOwner)하고 받는 사람이 수락해야 넘어가므로,
 * 두 사람의 연결 토큰으로 서버가 요청과 수락을 이어서 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveOwnershipService {

    private static final Provider PROVIDER = Provider.GOOGLE_DRIVE;
    /** 한 번에 확인하는 항목 수와 넘기는 항목 수 (Drive API 호출이 몰리지 않게) */
    private static final int MAX_ITEMS = 2000;
    private static final int MAX_TRANSFERS_PER_PASS = 100;

    private final DriveApiClient driveApiClient;
    private final DriveOwnerAccess ownerAccess;
    private final DriveTreeLocator locator;
    private final DriveWorkspaceSettingRepository settingRepository;
    private final DriveOwnershipJobRepository jobRepository;
    private final IntegrationService integrationService;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final UserService userService;
    private final JdbcTemplate jdbc;

    public record PassResult(int transferred, int remaining, int failed) {
    }

    // ===================== 정리 =====================

    /** 팀장 소유가 아닌 항목을 팀장에게 넘긴다. 인계 작업이 진행 중이면 건너뛴다. */
    public PassResult consolidate(Long workspaceId) {
        if (jobRepository.findFirstByWorkspaceIdAndStatus(workspaceId, Status.RUNNING).isPresent()) {
            return new PassResult(0, 0, 0);
        }
        Optional<OwnerToken> found = ownerAccess.findOwnerToken(workspaceId);
        if (found.isEmpty() || found.get().email() == null) {
            return new PassResult(0, 0, 0);
        }
        OwnerToken owner = found.get();
        List<DriveItem> notMine = teamItems(workspaceId, owner.accessToken()).stream()
            .filter(item -> !item.isOwnedBy(owner.email()))
            .toList();

        Map<String, Long> members = memberAccounts(workspaceId);
        int transferred = 0;
        int failed = 0;
        for (DriveItem item : notMine) {
            if (transferred + failed >= MAX_TRANSFERS_PER_PASS) {
                break;
            }
            Optional<OwnAccess> from = item.ownerEmails().stream()
                .map(email -> members.get(email.toLowerCase(Locale.ROOT)))
                .filter(id -> id != null)
                .findFirst()
                .flatMap(id -> ownerAccess.memberAccess(workspaceId, id));
            if (from.isEmpty()) {
                continue; // 원래 소유자가 연동하지 않았으면 넘길 수 없다. 팀장 소유 아님으로 남긴다.
            }
            try {
                transfer(item.id(), from.get().accessToken(), owner.accessToken(), owner.email());
                transferred++;
            } catch (BusinessException e) {
                failed++;
                log.warn("Drive 소유권 정리 실패: workspaceId={}, itemId={}, error={}", workspaceId, item.id(), e.getMessage());
            }
        }
        int remaining = notMine.size() - transferred;
        DriveWorkspaceSetting setting = settingRepository.findById(workspaceId)
            .orElseGet(() -> new DriveWorkspaceSetting(workspaceId, owner.userId()));
        setting.recordConsolidation(remaining, now());
        settingRepository.save(setting);
        if (transferred > 0) {
            log.info("Drive 소유권 정리: workspaceId={}, transferred={}, remaining={}", workspaceId, transferred, remaining);
        }
        return new PassResult(transferred, remaining, failed);
    }

    // ===================== 인계 =====================

    /** 진행 중인 인계 작업을 한 번씩 처리한다. */
    public void processRunningJobs() {
        for (DriveOwnershipJob job : jobRepository.findAllByStatus(Status.RUNNING)) {
            try {
                processJob(job.getJobId());
            } catch (RuntimeException e) {
                log.warn("Drive 소유권 인계 처리 실패: jobId={}, error={}", job.getJobId(), e.getMessage());
            }
        }
    }

    public void processJob(Long jobId) {
        DriveOwnershipJob job = jobRepository.findById(jobId).orElseThrow();
        if (!job.isRunning()) {
            return;
        }
        Long workspaceId = job.getWorkspaceId();
        Optional<OwnAccess> from = ownerAccess.memberAccess(workspaceId, job.getFromUserId());
        Optional<OwnAccess> to = ownerAccess.memberAccess(workspaceId, job.getToUserId());
        if (from.isEmpty()) {
            // 이전 팀장 토큰 없이는 넘길 방법이 없다. 계속 진행 중으로 두면 팀장 넘기기·연결 해제가 막히므로 끝낸다.
            job.recordPass(0, 0, job.getRemainingCount(),
                "이전 팀장의 Drive 연결을 사용할 수 없어 남은 항목은 이전 팀장 소유로 남았습니다.");
            finish(job);
            return;
        }
        if (to.isEmpty() || to.get().externalAccountId() == null) {
            job.recordPass(0, Math.max(job.getRemainingCount(), 1), 0, "새 팀장의 Drive 연결을 사용할 수 없습니다.");
            jobRepository.save(job);
            return;
        }
        String fromEmail = from.get().externalAccountId();
        String toEmail = to.get().externalAccountId();
        List<DriveItem> owned = teamItems(workspaceId, from.get().accessToken()).stream()
            .filter(item -> fromEmail != null && item.isOwnedBy(fromEmail))
            .toList();

        int transferred = 0;
        int failed = 0;
        String lastError = null;
        for (DriveItem item : owned) {
            if (transferred + failed >= MAX_TRANSFERS_PER_PASS) {
                break;
            }
            try {
                transfer(item.id(), from.get().accessToken(), to.get().accessToken(), toEmail);
                transferred++;
            } catch (BusinessException e) {
                failed++;
                lastError = e.getMessage();
                log.warn("Drive 소유권 인계 실패: jobId={}, itemId={}, error={}", jobId, item.id(), e.getMessage());
            }
        }
        int remaining = owned.size() - transferred;
        job.recordPass(transferred, remaining, failed, lastError);
        if (job.isRunning()) {
            jobRepository.save(job);
        } else {
            finish(job);
        }
    }

    /** 인계를 끝내고 Drive 소유자를 새 팀장으로 바꾼다. 토큰 계정이 바뀌므로 다음 동기화는 전체 동기화다. */
    private void finish(DriveOwnershipJob job) {
        jobRepository.save(job);
        Long workspaceId = job.getWorkspaceId();
        DriveWorkspaceSetting setting = settingRepository.findById(workspaceId)
            .orElseGet(() -> new DriveWorkspaceSetting(workspaceId, job.getToUserId()));
        setting.changeOwner(job.getToUserId());
        settingRepository.save(setting);
        ownerAccess.resetSyncCursors(workspaceId);
        log.info("Drive 소유권 인계 끝: workspaceId={}, from={}, to={}, items={}, error={}",
            workspaceId, job.getFromUserId(), job.getToUserId(), job.getDoneCount(), job.getLastError());
    }

    // ===================== 상태 =====================

    public DriveOwnershipStatus status(Long workspaceId, Long userId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        Optional<DriveWorkspaceSetting> setting = settingRepository.findById(workspaceId);
        Optional<Long> owner = ownerAccess.ownerUserId(workspaceId);
        Optional<DriveOwnershipJob> job = jobRepository.findFirstByWorkspaceIdOrderByJobIdDesc(workspaceId);

        List<Long> ids = new ArrayList<>();
        owner.ifPresent(ids::add);
        job.ifPresent(j -> {
            ids.add(j.getFromUserId());
            ids.add(j.getToUserId());
        });
        Map<Long, String> names = ids.isEmpty() ? Map.of() : userService.getUsers(ids.stream().distinct().toList())
            .stream().collect(Collectors.toMap(UserSummary::userId, UserSummary::name, (a, b) -> a));

        DriveOwnershipStatus.Transfer transfer = job.map(j -> new DriveOwnershipStatus.Transfer(
            j.getStatus().name(), j.getFromUserId(), names.get(j.getFromUserId()), j.getToUserId(),
            names.get(j.getToUserId()), j.getDoneCount(), j.getRemainingCount(), j.getFailedCount(), j.getLastError()))
            .orElse(null);
        return new DriveOwnershipStatus(
            owner.orElse(null),
            owner.map(names::get).orElse(null),
            owner.map(id -> integrationService.hasConnection(workspaceId, id, PROVIDER)).orElse(false),
            setting.map(DriveWorkspaceSetting::getTrashFolderId).isPresent(),
            setting.map(DriveWorkspaceSetting::getPendingOwnershipCount).orElse(0),
            setting.map(DriveWorkspaceSetting::getLastConsolidatedAt).orElse(null),
            transfer);
    }

    /** 팀장이 정리를 바로 실행한다. */
    public PassResult consolidateNow(Long workspaceId, Long userId) {
        workspaceAccessChecker.checkMainOwner(workspaceId, userId);
        return consolidate(workspaceId);
    }

    // ===================== 내부 =====================

    /** 받는 사람을 편집자로 두고, 현재 소유자가 이전을 요청하고, 받는 사람이 수락한다. */
    private void transfer(String itemId, String fromToken, String toToken, String toEmail) {
        String permissionId = driveApiClient.ensureWriter(fromToken, itemId, toEmail);
        driveApiClient.requestOwnership(fromToken, itemId, permissionId);
        driveApiClient.acceptOwnership(toToken, itemId, permissionId);
    }

    /** 연동 폴더 아래 전체와 휴지통 폴더 아래 전체 */
    private List<DriveItem> teamItems(Long workspaceId, String token) {
        List<String> starts = new ArrayList<>(integrationService.getConnectionTargets(workspaceId, PROVIDER));
        ownerAccess.trashFolderId(workspaceId).ifPresent(starts::add);
        if (starts.isEmpty()) {
            return List.of();
        }
        return locator.collect(token, starts, MAX_ITEMS);
    }

    /** 팀원의 Drive 연결 계정(메일, 소문자) → 사용자 ID */
    private Map<String, Long> memberAccounts(Long workspaceId) {
        Map<String, Long> accounts = new HashMap<>();
        for (Long userId : jdbc.queryForList("SELECT user_id FROM workspace_member WHERE workspace_id = ?",
            Long.class, workspaceId)) {
            ownerAccess.accountEmail(workspaceId, userId)
                .ifPresent(email -> accounts.put(email.toLowerCase(Locale.ROOT), userId));
        }
        return accounts;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 팀장을 넘길 때 Drive를 쓰는 워크스페이스인지 */
    boolean usesDrive(Long workspaceId) {
        return !integrationService.getConnectionTargets(workspaceId, PROVIDER).isEmpty();
    }

    void requireNoRunningJob(Long workspaceId) {
        if (jobRepository.findFirstByWorkspaceIdAndStatus(workspaceId, Status.RUNNING).isPresent()) {
            throw new BusinessException(ErrorCode.DRIVE_OWNERSHIP_TRANSFER_IN_PROGRESS);
        }
    }
}
