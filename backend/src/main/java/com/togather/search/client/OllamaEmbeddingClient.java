package com.togather.search.client;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.search.config.SearchProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * BGE-M3 임베딩 (Ollama로 실행). 다국어 모델이라 한국어 검색에 쓴다.
 * 실행: ollama pull bge-m3 (Ollama가 켜져 있으면 localhost:11434에서 응답한다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final RestClient searchRestClient;
    private final SearchProperties properties;

    private record EmbedResponse(List<List<Double>> embeddings) {
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        EmbedResponse response;
        try {
            response = searchRestClient.post()
                    .uri(properties.ollamaUrl() + "/api/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.embeddingModel(), "input", texts))
                    .retrieve()
                    .body(EmbedResponse.class);
        } catch (RestClientException e) {
            log.warn("임베딩 호출 실패(Ollama {}): {}", properties.embeddingModel(), e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
        if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
            log.warn("임베딩 개수 불일치: requested={}", texts.size());
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
        return response.embeddings().stream().map(OllamaEmbeddingClient::toVector).toList();
    }

    private static float[] toVector(List<Double> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }
}
