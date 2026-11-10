package com.togather.task.dto;

import com.togather.content.dto.ContentRefDTO;
import java.time.LocalDateTime;

public record TaskReferenceCandidate(
    Long contentRefId,
    String title,
    String deepLink,
    LocalDateTime lastModifiedAt
) {

    public static TaskReferenceCandidate from(ContentRefDTO ref) {
        return new TaskReferenceCandidate(ref.contentRefId(), ref.title(), ref.deepLink(), ref.lastModifiedAt());
    }
}
