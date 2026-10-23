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
    boolean submitted
) {

    public static TaskDeliverableSummary from(TaskDeliverable deliverable) {
        return new TaskDeliverableSummary(
            deliverable.getDeliverableId(),
            deliverable.getName(),
            deliverable.getExt(),
            deliverable.getFileId(),
            deliverable.getFileName(),
            deliverable.getLink(),
            deliverable.getSubmittedBy(),
            deliverable.getSubmittedAt(),
            deliverable.isSubmitted()
        );
    }
}
