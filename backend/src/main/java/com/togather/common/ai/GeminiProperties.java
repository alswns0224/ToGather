package com.togather.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 togather.gemini.* 설정 (B 담당).
 *
 * @param model          텍스트 생성 모델 (논의 분석, 후보 추출)
 * @param embeddingModel 임베딩 모델 (통합 검색)
 * @param timeoutSeconds 재시도를 포함한 전체 대기 시간. 논의 분석 30초 목표에 맞춘다.
 * @param maxRetries     429·5xx·타임아웃일 때 다시 시도하는 최대 횟수 (남은 시간이 있을 때만)
 */
@ConfigurationProperties(prefix = "togather.gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String model,
        String embeddingModel,
        Integer timeoutSeconds,
        Integer maxRetries
) {

    public GeminiProperties {
        baseUrl = blankToDefault(baseUrl, "https://generativelanguage.googleapis.com/v1beta");
        model = blankToDefault(model, "gemini-2.5-flash");
        embeddingModel = blankToDefault(embeddingModel, "gemini-embedding-001");
        timeoutSeconds = timeoutSeconds == null ? 30 : timeoutSeconds;
        maxRetries = maxRetries == null ? 2 : maxRetries;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 키가 로그에 남지 않게 한다. */
    @Override
    public String toString() {
        return "GeminiProperties[model=" + model + ", embeddingModel=" + embeddingModel + "]";
    }
}
