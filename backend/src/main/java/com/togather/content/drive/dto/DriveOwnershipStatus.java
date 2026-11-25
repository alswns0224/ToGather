package com.togather.content.drive.dto;

import java.time.LocalDateTime;

/**
 * Drive 소유자(팀장)와 소유권 정리 상태.
 *
 * @param pendingOwnershipCount 아직 팀장 소유가 아닌 항목 수 (마지막 정리 기준)
 * @param transfer 팀장을 넘기며 진행 중이거나 마지막으로 끝난 소유권 인계. 없으면 null
 */
public record DriveOwnershipStatus(
    Long ownerUserId,
    String ownerName,
    boolean ownerConnected,
    boolean trashFolderReady,
    int pendingOwnershipCount,
    LocalDateTime lastConsolidatedAt,
    Transfer transfer
) {
    public record Transfer(
        String status,
        Long fromUserId,
        String fromName,
        Long toUserId,
        String toName,
        int doneCount,
        int remainingCount,
        int failedCount,
        String lastError
    ) {
    }
}
