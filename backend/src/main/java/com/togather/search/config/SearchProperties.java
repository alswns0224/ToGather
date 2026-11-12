package com.togather.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 togather.search.* 설정 (C-07·08, B 담당).
 * 임베딩은 BGE-M3(Ollama로 실행), 벡터 저장·검색은 Chroma를 쓴다. (주제제안서 기술 스택)
 *
 * @param enabled        false면 색인·검색을 하지 않는다 (Ollama·Chroma가 없는 환경)
 * @param ollamaUrl      Ollama 주소
 * @param embeddingModel Ollama 임베딩 모델 이름
 * @param chromaUrl      Chroma 서버 주소
 * @param collection     Chroma 컬렉션 이름
 * @param batchSize      한 번에 임베딩하는 콘텐츠 수
 * @param minScore       이보다 관련도(코사인 유사도)가 낮은 결과는 보여주지 않는다
 */
@ConfigurationProperties(prefix = "togather.search")
public record SearchProperties(
        Boolean enabled,
        String ollamaUrl,
        String embeddingModel,
        String chromaUrl,
        String collection,
        Integer batchSize,
        Double minScore
) {

    public SearchProperties {
        enabled = enabled == null || enabled;
        ollamaUrl = blankToDefault(ollamaUrl, "http://127.0.0.1:11434");
        embeddingModel = blankToDefault(embeddingModel, "bge-m3");
        chromaUrl = blankToDefault(chromaUrl, "http://127.0.0.1:8000");
        collection = blankToDefault(collection, "togather_contents");
        batchSize = batchSize == null ? 32 : batchSize;
        minScore = minScore == null ? 0.35 : minScore;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
