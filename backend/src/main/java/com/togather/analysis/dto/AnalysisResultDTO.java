package com.togather.analysis.dto;

import com.togather.common.enums.AnalysisStatus;
import com.togather.common.enums.OutputStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 분석 결과 (항목·근거 발언 포함)
 *
 * @param analyzedUntil 이 시각까지의 메시지를 분석했다. 이후 메시지는 다음 분석 대상
 * @param messages      스레드 메시지 (근거 연결용)
 */
public record AnalysisResultDTO(Long analysisId, Long contentRefId, String channelId, String threadTitle,
                                String threadPreview, String threadLink, AnalysisStatus status, boolean auto,
                                LocalDateTime analyzedUntil, LocalDateTime createdAt, List<AnalysisItemResponse> items,
                                List<ThreadMessageResponse> messages, Long notionPageRefId,
                                OutputStatus notionOutputStatus) {
}
