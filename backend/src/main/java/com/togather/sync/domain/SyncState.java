package com.togather.sync.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.Provider;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스·연동 대상별 증분 동기화 상태 (B-03).
 * 팀 서비스는 ownerUserId가 null, 개인 서비스(Gmail 등)는 사용자마다 하나씩 둔다.
 */
@Getter
@Entity
@Table(name = "sync_state")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncState extends BaseTimeEntity {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long syncStateId;

    private Long workspaceId;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    /** 대상이 없는 서비스는 "" */
    private String targetId;

    private Long ownerUserId;

    /** 다음 증분 동기화의 기준 시각. 한 번도 성공하지 않았으면 null(전체 동기화) */
    private LocalDateTime lastSyncedAt;

    /** 서비스가 주는 동기화 위치 (예: Gmail historyId). 시각으로 이어받는 서비스는 null */
    private String syncCursor;

    private LocalDateTime lastAttemptAt;

    private int failureCount;

    private String lastError;

    public SyncState(Long workspaceId, Provider provider, String targetId, Long ownerUserId) {
        this.workspaceId = workspaceId;
        this.provider = provider;
        this.targetId = targetId == null ? "" : targetId;
        this.ownerUserId = ownerUserId;
    }

    /**
     * @param syncedUntil 이번 동기화가 반영한 시점. 다음에는 이 시각 이후만 가져온다.
     * @param syncCursor  다음 동기화 위치 (cursor를 쓰지 않는 서비스는 null)
     */
    public void recordSuccess(LocalDateTime syncedUntil, String syncCursor, LocalDateTime attemptedAt) {
        this.lastSyncedAt = syncedUntil;
        this.syncCursor = syncCursor;
        this.lastAttemptAt = attemptedAt;
        this.failureCount = 0;
        this.lastError = null;
    }

    /** 실패해도 lastSyncedAt은 그대로 두어 다음 동기화에서 같은 범위를 다시 가져온다. */
    public void recordFailure(String error, LocalDateTime attemptedAt) {
        this.lastAttemptAt = attemptedAt;
        this.failureCount++;
        this.lastError = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
