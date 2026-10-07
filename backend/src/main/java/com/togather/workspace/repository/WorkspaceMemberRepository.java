package com.togather.workspace.repository;

import com.togather.workspace.domain.WorkspaceMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    List<WorkspaceMember> findAllByWorkspaceId(Long workspaceId);

    List<WorkspaceMember> findAllByUserId(Long userId);
}
