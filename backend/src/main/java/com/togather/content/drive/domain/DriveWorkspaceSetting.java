package com.togather.content.drive.domain;

import com.togather.common.domain.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 워크스페이스의 Drive 소유자(팀장)와 휴지통 폴더. 팀장을 넘기는 동안에는 소유권 인계가 끝날 때까지 이전 팀장이다. */
@Getter
@Entity
@Table(name = "drive_workspace_setting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriveWorkspaceSetting extends BaseTimeEntity {

    @Id
    private Long workspaceId;

    private Long ownerUserId;

    private String trashFolderId;

    private int pendingOwnershipCount;

    private LocalDateTime lastConsolidatedAt;

    public DriveWorkspaceSetting(Long workspaceId, Long ownerUserId) {
        this.workspaceId = workspaceId;
        this.ownerUserId = ownerUserId;
    }

    public void changeOwner(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public void changeTrashFolder(String trashFolderId) {
        this.trashFolderId = trashFolderId;
    }

    public void recordConsolidation(int pendingOwnershipCount, LocalDateTime at) {
        this.pendingOwnershipCount = pendingOwnershipCount;
        this.lastConsolidatedAt = at;
    }
}
