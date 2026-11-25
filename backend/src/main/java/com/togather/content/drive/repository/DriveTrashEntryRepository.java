package com.togather.content.drive.repository;

import com.togather.content.drive.domain.DriveTrashEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveTrashEntryRepository extends JpaRepository<DriveTrashEntry, Long> {

    Optional<DriveTrashEntry> findByWorkspaceIdAndItemId(Long workspaceId, String itemId);

    List<DriveTrashEntry> findAllByWorkspaceId(Long workspaceId);

    void deleteByWorkspaceIdAndItemId(Long workspaceId, String itemId);
}
