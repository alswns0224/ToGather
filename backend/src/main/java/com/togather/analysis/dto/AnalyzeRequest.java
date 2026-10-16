package com.togather.analysis.dto;

import jakarta.validation.constraints.NotNull;

/** 스레드 즉시 분석. contentRefId는 Slack 스레드의 첫 메시지 */
public record AnalyzeRequest(@NotNull Long contentRefId) {
}
