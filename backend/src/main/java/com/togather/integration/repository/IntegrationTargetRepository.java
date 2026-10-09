package com.togather.integration.repository;

import com.togather.integration.domain.IntegrationTarget;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationTargetRepository extends JpaRepository<IntegrationTarget, Long> {

    List<IntegrationTarget> findAllByConnectionId(Long connectionId);

    List<IntegrationTarget> findAllByConnectionIdIn(Collection<Long> connectionIds);

    void deleteAllByConnectionId(Long connectionId);
}
