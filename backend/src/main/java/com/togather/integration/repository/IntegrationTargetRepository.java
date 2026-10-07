package com.togather.integration.repository;

import com.togather.integration.domain.IntegrationTarget;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationTargetRepository extends JpaRepository<IntegrationTarget, Long> {
}
