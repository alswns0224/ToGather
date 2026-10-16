package com.togather.assistant.dto;

import java.util.List;

/**
 * 후보 탐지 결과
 *
 * @param scannedMessages 이번에 새로 살펴본 메시지 수
 * @param created         새로 만든 후보 (중복은 제외)
 */
public record DetectResultResponse(int scannedMessages, List<SuggestionDTO> created) {
}
