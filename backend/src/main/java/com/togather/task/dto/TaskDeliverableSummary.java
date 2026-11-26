package com.togather.task.dto;

import com.togather.task.domain.TaskDeliverable;
import java.time.LocalDateTime;

public record TaskDeliverableSummary(
    Long deliverableId,
    String name,
    String ext,
    String fileId,
    String fileName,
    String link,
    Long submittedBy,
    LocalDateTime submittedAt,
    boolean submitted,
    /** 제출한 Drive 파일 상태: TRASHED(휴지통으로 이동됨), DELETED(원본을 확인할 수 없음), 그 밖에는 null */
    String fileStatus
) {

    public static TaskDeliverableSummary from(TaskDeliverable deliverable) {
        return from(deliverable, null);
    }

    public static TaskDeliverableSummary from(TaskDeliverable deliverable, String fileStatus) {
        return new TaskDeliverableSummary(
            deliverable.getDeliverableId(),
            deliverable.getName(),
            deliverable.getExt(),
            deliverable.getFileId(),
            deliverable.getFileName(),
            deliverable.getLink(),
            deliverable.getSubmittedBy(),
            deliverable.getSubmittedAt(),
            deliverable.isSubmitted(),
            fileStatus
        );
    }
}
