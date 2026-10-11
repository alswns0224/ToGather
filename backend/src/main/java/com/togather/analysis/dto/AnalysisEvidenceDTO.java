package com.togather.analysis.dto;

import java.time.LocalDateTime;

/** 근거 발언 (Slack 메시지) */
public record AnalysisEvidenceDTO(Long evidenceId, Long itemId, Long contentRefId, String authorName, String text,
                                  LocalDateTime createdAt, String deepLink, boolean isOriginDeleted) {
}
