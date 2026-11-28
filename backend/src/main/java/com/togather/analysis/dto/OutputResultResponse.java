package com.togather.analysis.dto;

import com.togather.common.enums.OutputStatus;

/**
 * 연계 단계 결과
 *
 * @param targetId Task ID, 회의 ID 또는 생성된 Notion 문서의 ContentRef ID
 */
public record OutputResultResponse(OutputStatus status, Long targetId, String link) {
}
