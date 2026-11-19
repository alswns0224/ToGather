package com.togather.integration.repository;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.integration.domain.ServiceConnection;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ServiceConnectionRepository extends JpaRepository<ServiceConnection, Long> {

    /** 예약 획득 직전 읽은 Entity가 오래된 상태여도, 해제 후 토큰이 나가지 않게 현재 상태를 재조회한다. */
    @Query("select c.status from ServiceConnection c where c.connectionId = :connectionId")
    Optional<ConnectionStatus> findCurrentStatus(Long connectionId);

    Optional<ServiceConnection> findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
            Long workspaceId, Long userId, Provider provider, ConnectionStatus status);

    List<ServiceConnection> findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(
            Long workspaceId, Provider provider, ConnectionStatus status);

    List<ServiceConnection> findAllByWorkspaceIdAndStatusNot(Long workspaceId, ConnectionStatus status);

    /** 외부 계정(예: Notion 워크스페이스 ID)으로 연결을 찾는다. 외부 서비스 웹훅을 워크스페이스에 나눠 줄 때 쓴다. */
    List<ServiceConnection> findAllByProviderAndExternalAccountIdAndStatusNot(
            Provider provider, String externalAccountId, ConnectionStatus status);

    List<ServiceConnection> findAllByProviderInAndExternalAccountIdAndStatusNot(
            Collection<Provider> providers, String externalAccountId, ConnectionStatus status);
}
