package com.togather.sync.repository;

import com.togather.common.enums.Provider;
import com.togather.sync.domain.SyncState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncStateRepository extends JpaRepository<SyncState, Long> {

    Optional<SyncState> findByWorkspaceIdAndProviderAndTargetIdAndOwnerUserIdIsNull(
            Long workspaceId, Provider provider, String targetId);

    Optional<SyncState> findByWorkspaceIdAndProviderAndTargetIdAndOwnerUserId(
            Long workspaceId, Provider provider, String targetId, Long ownerUserId);

    List<SyncState> findAllByWorkspaceIdAndProvider(Long workspaceId, Provider provider);

    /** 팀 콘텐츠는 ownerUserId가 null */
    default Optional<SyncState> findState(Long workspaceId, Provider provider, String targetId, Long ownerUserId) {
        return ownerUserId == null
                ? findByWorkspaceIdAndProviderAndTargetIdAndOwnerUserIdIsNull(workspaceId, provider, targetId)
                : findByWorkspaceIdAndProviderAndTargetIdAndOwnerUserId(workspaceId, provider, targetId, ownerUserId);
    }
}
