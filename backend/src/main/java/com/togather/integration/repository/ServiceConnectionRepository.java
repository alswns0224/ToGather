package com.togather.integration.repository;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.integration.domain.ServiceConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceConnectionRepository extends JpaRepository<ServiceConnection, Long> {

    Optional<ServiceConnection> findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
            Long workspaceId, Long userId, Provider provider, ConnectionStatus status);

    List<ServiceConnection> findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(
            Long workspaceId, Provider provider, ConnectionStatus status);

    List<ServiceConnection> findAllByWorkspaceIdAndStatusNot(Long workspaceId, ConnectionStatus status);
}
