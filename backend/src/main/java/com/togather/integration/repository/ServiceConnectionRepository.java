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

    /** 외부 계정(예: Notion 워크스페이스 ID)으로 연결을 찾는다. 외부 서비스 웹훅을 워크스페이스에 나눠 줄 때 쓴다. */
    List<ServiceConnection> findAllByProviderAndExternalAccountIdAndStatusNot(
            Provider provider, String externalAccountId, ConnectionStatus status);
}
