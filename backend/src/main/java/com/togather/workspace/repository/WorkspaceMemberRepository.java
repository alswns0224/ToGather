package com.togather.workspace.repository;

import com.togather.common.enums.WorkspaceRole;
import com.togather.workspace.domain.WorkspaceMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    List<WorkspaceMember> findAllByWorkspaceId(Long workspaceId);

    List<WorkspaceMember> findAllByUserId(Long userId);

    Optional<WorkspaceMember> findFirstByWorkspaceIdAndRole(Long workspaceId, WorkspaceRole role);

    long countByWorkspaceId(Long workspaceId);

    /** [workspaceId, 팀원 수] 목록 */
    @Query("select m.workspaceId, count(m) from WorkspaceMember m where m.workspaceId in :workspaceIds group by m.workspaceId")
    List<Object[]> countByWorkspaceIds(@Param("workspaceIds") Collection<Long> workspaceIds);
}
