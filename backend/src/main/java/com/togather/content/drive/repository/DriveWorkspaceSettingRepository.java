package com.togather.content.drive.repository;

import com.togather.content.drive.domain.DriveWorkspaceSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveWorkspaceSettingRepository extends JpaRepository<DriveWorkspaceSetting, Long> {
}
