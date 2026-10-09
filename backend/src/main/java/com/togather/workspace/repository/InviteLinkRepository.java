package com.togather.workspace.repository;

import com.togather.workspace.domain.InviteLink;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteLinkRepository extends JpaRepository<InviteLink, Long> {

    Optional<InviteLink> findByToken(String token);

    Optional<InviteLink> findFirstByWorkspaceIdAndExpiresAtAfterOrderByExpiresAtDesc(Long workspaceId, LocalDateTime now);
}
