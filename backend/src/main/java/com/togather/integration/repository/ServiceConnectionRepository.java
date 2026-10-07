package com.togather.integration.repository;

import com.togather.integration.domain.ServiceConnection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceConnectionRepository extends JpaRepository<ServiceConnection, Long> {
}
