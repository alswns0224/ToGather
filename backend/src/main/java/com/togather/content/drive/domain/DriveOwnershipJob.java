package com.togather.content.drive.domain;

import com.togather.common.domain.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 팀장을 넘길 때 이전 팀장 소유의 Drive 항목을 새 팀장에게 넘기는 작업. 남은 항목이 없으면 끝난다. */
@Getter
@Entity
@Table(name = "drive_ownership_job")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriveOwnershipJob extends BaseTimeEntity {

    private static final int MAX_ERROR_LENGTH = 500;

    public enum Status { RUNNING, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long jobId;

    private Long workspaceId;

    private Long fromUserId;

    private Long toUserId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private int doneCount;

    private int remainingCount;

    private int failedCount;

    private String lastError;

    public DriveOwnershipJob(Long workspaceId, Long fromUserId, Long toUserId) {
        this.workspaceId = workspaceId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.status = Status.RUNNING;
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public boolean involves(Long userId) {
        return fromUserId.equals(userId) || toUserId.equals(userId);
    }

    /** 한 번 처리한 결과. 남은 항목이 없으면 끝낸다. */
    public void recordPass(int transferred, int remaining, int failed, String error) {
        this.doneCount += transferred;
        this.remainingCount = remaining;
        this.failedCount = failed;
        this.lastError = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        if (remaining == 0) {
            this.status = Status.DONE;
        }
    }
}
