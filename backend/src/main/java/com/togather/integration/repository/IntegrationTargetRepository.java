package com.togather.integration.repository;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.integration.domain.IntegrationTarget;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IntegrationTargetRepository extends JpaRepository<IntegrationTarget, Long> {

    List<IntegrationTarget> findAllByConnectionId(Long connectionId);

    List<IntegrationTarget> findAllByConnectionIdIn(Collection<Long> connectionIds);

    void deleteAllByConnectionId(Long connectionId);

    /** 이 외부 대상(예: Slack 채널)을 연동 대상으로 고른 워크스페이스 (해제된 연결 제외) */
    @Query("""
            select distinct c.workspaceId from IntegrationTarget t, ServiceConnection c
            where t.connectionId = c.connectionId and c.provider = :provider and c.status <> :disconnected
              and t.externalTargetId = :externalTargetId
            """)
    List<Long> findWorkspaceIdsByTarget(Provider provider, String externalTargetId, ConnectionStatus disconnected);
}
