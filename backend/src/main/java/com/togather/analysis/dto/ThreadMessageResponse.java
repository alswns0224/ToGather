package com.togather.analysis.dto;

import java.time.LocalDateTime;

/** 분석 화면에서 근거로 고를 수 있는 스레드 메시지 */
public record ThreadMessageResponse(Long contentRefId, String authorName, String text, LocalDateTime createdAt,
                                    boolean analyzed) {
}
