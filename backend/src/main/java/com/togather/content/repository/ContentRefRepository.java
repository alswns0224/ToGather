package com.togather.content.repository;

import com.togather.common.enums.Provider;
import com.togather.content.domain.ContentRef;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRefRepository extends JpaRepository<ContentRef, Long> {

    Optional<ContentRef> findByWorkspaceIdAndProviderAndOriginId(Long workspaceId, Provider provider, String originId);
}
