package com.togather.sync.service;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.domain.SyncState;
import com.togather.sync.dto.SyncStatusResponse;
import com.togather.sync.service.SyncAdapter.SyncContext;
import com.togather.sync.service.SyncAdapter.SyncResult;
import com.togather.sync.service.SyncAdapter.SyncTarget;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 증분 동기화 실행 (B-03).
 *
 * <ul>
 *   <li>대상마다 마지막 성공 시각(lastSyncedAt) 이후만 가져온다.</li>
 *   <li>외부 API가 실패하면 이미 저장된 콘텐츠는 그대로 두고 실패만 기록한다. 다음 동기화에서 같은 범위를 다시 가져온다.</li>
 *   <li>같은 대상을 동시에 두 번 동기화하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class SyncService {

    /** 연속 실패 시 다음 자동 동기화까지 기다리는 최대 시간 */
    private static final Duration MAX_BACKOFF = Duration.ofHours(2);
    private static final Duration BASE_BACKOFF = Duration.ofMinutes(5);
    /** 사용자가 요청해도 이 간격 안에는 다시 동기화하지 않는다. (화면 진입마다 호출되므로 외부 API 호출 한도 보호) */
    static final Duration MIN_MANUAL_INTERVAL = Duration.ofMinutes(1);

    private final Map<Provider, SyncAdapter> adapters;
    private final SyncStateService syncStateService;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public SyncService(ObjectProvider<SyncAdapter> adapters, SyncStateService syncStateService) {
        this.adapters = adapters.orderedStream()
                .collect(Collectors.toMap(SyncAdapter::provider, Function.identity()));
        this.syncStateService = syncStateService;
    }

    public boolean supports(Provider provider) {
        return adapters.containsKey(provider);
    }

    /**
     * 사용자가 요청한 즉시 동기화. 대상별 결과를 돌려준다. (실패해도 예외를 던지지 않는다)
     * 1분 안에 동기화를 시도한 대상은 건너뛴다.
     */
    public List<SyncStatusResponse> syncNow(Long workspaceId, Provider provider, Long requestedBy) {
        syncWorkspace(workspaceId, provider, requestedBy, false);
        return getStatuses(workspaceId, provider);
    }

    /**
     * 마지막 동기화가 maxAge보다 오래됐거나 한 번도 안 했으면 동기화한다. 화면 진입 시 사용한다.
     * 대상이 하나도 없으면(연동 전) 아무것도 하지 않는다.
     */
    public void syncIfStale(Long workspaceId, Provider provider, Long requestedBy, Duration maxAge) {
        SyncAdapter adapter = adapter(provider);
        LocalDateTime threshold = LocalDateTime.now().minus(maxAge);
        Map<String, SyncState> states = statesByKey(workspaceId, provider);
        boolean stale = targets(adapter, workspaceId, requestedBy).stream().anyMatch(target -> {
            SyncState state = states.get(key(target));
            return state == null || state.getLastAttemptAt() == null || state.getLastAttemptAt().isBefore(threshold);
        });
        if (stale) {
            syncWorkspace(workspaceId, provider, requestedBy, false);
        }
    }

    /**
     * 외부 서비스 이벤트(예: Slack 새 메시지)를 받았을 때 그 대상만 바로 동기화한다. 1분 간격 제한을 두지 않는다.
     * 이미 동기화 중이면 그 동기화가 새 변경을 놓쳤을 수 있으므로, 끝날 때까지 잠깐 기다렸다가 다시 동기화한다.
     * (이벤트 처리 스레드에서 부른다. 요청 스레드에서 부르지 않는다)
     */
    public void syncTargetNow(Long workspaceId, Provider provider, String targetId) {
        SyncAdapter adapter = adapter(provider);
        SyncTarget target = SyncTarget.team(targetId);
        for (int attempt = 0; attempt < 20; attempt++) {
            if (syncTarget(adapter, workspaceId, target, null, false, true)) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("즉시 동기화 건너뜀(다른 동기화가 오래 걸림): workspaceId={}, provider={}, target={}",
                workspaceId, provider, targetId);
    }

    public List<SyncStatusResponse> getStatuses(Long workspaceId, Provider provider) {
        return syncStateService.getStates(workspaceId, provider).stream().map(SyncStatusResponse::from).toList();
    }

    /** 스케줄러: 한 번이라도 동기화한 워크스페이스·서비스를 다시 동기화한다. 연속 실패한 대상은 잠시 쉰다. */
    public void syncAllKnown() {
        Set<WorkspaceProvider> known = new LinkedHashSet<>();
        for (SyncState state : syncStateService.getAllStates()) {
            if (adapters.containsKey(state.getProvider())) {
                known.add(new WorkspaceProvider(state.getWorkspaceId(), state.getProvider()));
            }
        }
        for (WorkspaceProvider wp : known) {
            try {
                syncWorkspace(wp.workspaceId(), wp.provider(), null, true);
            } catch (RuntimeException e) {
                log.warn("자동 동기화 실패: workspaceId={}, provider={}, error={}",
                        wp.workspaceId(), wp.provider(), describe(e));
            }
        }
    }

    // ===================== 내부 =====================

    private void syncWorkspace(Long workspaceId, Provider provider, Long requestedBy, boolean scheduled) {
        SyncAdapter adapter = adapter(provider);
        for (SyncTarget target : targets(adapter, workspaceId, requestedBy)) {
            syncTarget(adapter, workspaceId, target, requestedBy, scheduled, false);
        }
    }

    /** 어댑터가 주는 대상 + 개인 서비스는 이미 동기화하던 사용자들 */
    private List<SyncTarget> targets(SyncAdapter adapter, Long workspaceId, Long requestedBy) {
        Set<SyncTarget> targets = new LinkedHashSet<>();
        try {
            targets.addAll(adapter.findTargets(workspaceId, requestedBy));
        } catch (BusinessException e) {
            // 연동이 없거나 재인증이 필요하면 대상이 없는 것으로 본다.
            log.info("동기화 대상 조회 생략: workspaceId={}, provider={}, error={}",
                    workspaceId, adapter.provider(), e.getErrorCode());
        }
        if (IntegrationService.PERSONAL_PROVIDERS.contains(adapter.provider())) {
            syncStateService.getStates(workspaceId, adapter.provider()).stream()
                    .filter(s -> s.getOwnerUserId() != null)
                    .forEach(s -> targets.add(SyncTarget.personal(s.getOwnerUserId())));
        }
        return new ArrayList<>(targets);
    }

    /**
     * @param force 간격 제한 없이 바로 동기화 (외부 이벤트)
     * @return 이번 호출이 동기화를 직접 실행했으면 true, 다른 동기화가 돌고 있어 건너뛰었으면 false
     */
    private boolean syncTarget(SyncAdapter adapter, Long workspaceId, SyncTarget target, Long requestedBy,
                               boolean scheduled, boolean force) {
        String lockKey = workspaceId + ":" + adapter.provider() + ":" + key(target);
        if (!running.add(lockKey)) {
            return false; // 이미 동기화 중
        }
        try {
            SyncState state = syncStateService.getOrCreate(workspaceId, adapter.provider(), target.targetId(),
                    target.ownerUserId());
            LocalDateTime now = LocalDateTime.now();
            if (!force && (scheduled ? isBackingOff(state, now) : isRecentlyAttempted(state, now))) {
                return true;
            }
            try {
                SyncResult result = adapter.sync(new SyncContext(workspaceId, target.targetId(), target.ownerUserId(),
                        state.getLastSyncedAt(), state.getSyncCursor(), requestedBy));
                LocalDateTime syncedUntil = result.syncedUntil() != null ? result.syncedUntil() : now;
                syncStateService.recordSuccess(state.getSyncStateId(), syncedUntil, result.syncCursor(), now);
                log.info("동기화 완료: workspaceId={}, provider={}, target={}, saved={}",
                        workspaceId, adapter.provider(), target.targetId(), result.savedCount());
            } catch (RuntimeException e) {
                syncStateService.recordFailure(state.getSyncStateId(), describe(e), now);
                log.warn("동기화 실패: workspaceId={}, provider={}, target={}, error={}",
                        workspaceId, adapter.provider(), target.targetId(), describe(e));
            }
        } finally {
            running.remove(lockKey);
        }
        return true;
    }

    static boolean isRecentlyAttempted(SyncState state, LocalDateTime now) {
        return state.getLastAttemptAt() != null && state.getLastAttemptAt().plus(MIN_MANUAL_INTERVAL).isAfter(now);
    }

    static boolean isBackingOff(SyncState state, LocalDateTime now) {
        if (state.getFailureCount() == 0 || state.getLastAttemptAt() == null) {
            return false;
        }
        long factor = 1L << Math.min(state.getFailureCount() - 1, 10);
        Duration wait = BASE_BACKOFF.multipliedBy(factor);
        if (wait.compareTo(MAX_BACKOFF) > 0) {
            wait = MAX_BACKOFF;
        }
        return state.getLastAttemptAt().plus(wait).isAfter(now);
    }

    /** 실패 기록용. 외부 응답 본문이나 토큰이 들어가지 않게 코드·예외 이름만 남긴다. */
    private static String describe(RuntimeException e) {
        return e instanceof BusinessException be ? be.getErrorCode().name() : e.getClass().getSimpleName();
    }

    private Map<String, SyncState> statesByKey(Long workspaceId, Provider provider) {
        return syncStateService.getStates(workspaceId, provider).stream()
                .collect(Collectors.toMap(s -> key(new SyncTarget(s.getTargetId(), s.getOwnerUserId())),
                        Function.identity(), (a, b) -> a));
    }

    private static String key(SyncTarget target) {
        return target.targetId() + "#" + Optional.ofNullable(target.ownerUserId()).map(String::valueOf).orElse("");
    }

    private SyncAdapter adapter(Provider provider) {
        SyncAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return adapter;
    }

    private record WorkspaceProvider(Long workspaceId, Provider provider) {
    }
}
