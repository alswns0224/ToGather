package com.togather.sync.service;

import com.togather.common.enums.Provider;
import com.togather.sync.domain.SyncState;
import com.togather.sync.repository.SyncStateRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기화 상태 저장. 동기화 본문이 실패해도 상태 기록은 남아야 하므로 각 메서드를 별도 트랜잭션으로 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SyncStateService {

    private final SyncStateRepository syncStateRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncState getOrCreate(Long workspaceId, Provider provider, String targetId, Long ownerUserId) {
        return syncStateRepository.findState(workspaceId, provider, targetId, ownerUserId)
                .orElseGet(() -> syncStateRepository.save(new SyncState(workspaceId, provider, targetId, ownerUserId)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long syncStateId, LocalDateTime syncedUntil, String syncCursor,
                              LocalDateTime attemptedAt) {
        syncStateRepository.findById(syncStateId).ifPresent(s -> s.recordSuccess(syncedUntil, syncCursor, attemptedAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long syncStateId, String error, LocalDateTime attemptedAt) {
        syncStateRepository.findById(syncStateId).ifPresent(s -> s.recordFailure(error, attemptedAt));
    }

    public List<SyncState> getStates(Long workspaceId, Provider provider) {
        return syncStateRepository.findAllByWorkspaceIdAndProvider(workspaceId, provider);
    }

    public List<SyncState> getAllStates() {
        return syncStateRepository.findAll();
    }
}
