package com.togather.content.drive.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** ToGather에서 휴지통 폴더로 옮긴 항목. 복원할 때 원래 위치로 되돌리는 데 쓴다. */
@Getter
@Entity
@Table(name = "drive_trash_entry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriveTrashEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long entryId;

    private Long workspaceId;

    private String itemId;

    private String itemName;

    private boolean folder;

    private String rootFolderId;

    private String originParentId;

    private Long trashedBy;

    private LocalDateTime trashedAt;

    public DriveTrashEntry(Long workspaceId, String itemId, String itemName, boolean folder, String rootFolderId,
                           String originParentId, Long trashedBy, LocalDateTime trashedAt) {
        this.workspaceId = workspaceId;
        this.itemId = itemId;
        this.itemName = itemName;
        this.folder = folder;
        this.rootFolderId = rootFolderId;
        this.originParentId = originParentId;
        this.trashedBy = trashedBy;
        this.trashedAt = trashedAt;
    }
}
