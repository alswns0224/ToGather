package com.togather.content.drive.repository;

import com.togather.content.drive.domain.DriveOwnershipJob;
import com.togather.content.drive.domain.DriveOwnershipJob.Status;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveOwnershipJobRepository extends JpaRepository<DriveOwnershipJob, Long> {

    Optional<DriveOwnershipJob> findFirstByWorkspaceIdAndStatus(Long workspaceId, Status status);

    Optional<DriveOwnershipJob> findFirstByWorkspaceIdOrderByJobIdDesc(Long workspaceId);

    List<DriveOwnershipJob> findAllByStatus(Status status);
}
