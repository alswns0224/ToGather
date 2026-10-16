package com.togather.analysis.dto;

import com.togather.common.enums.AnalysisStatus;
import java.time.LocalDateTime;

/** 검토 대기 목록 한 줄 (스레드별 최신 분석) */
public record AnalysisSummaryDTO(Long analysisId, Long contentRefId, String threadTitle, String threadPreview,
                                 AnalysisStatus status, boolean auto, long itemCount, LocalDateTime createdAt) {
}
