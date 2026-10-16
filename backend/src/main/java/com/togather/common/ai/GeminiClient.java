package com.togather.common.ai;

import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini API (docs/shared-interfaces.md [결정 7], B 담당).
 * 실패하면 AI_CALL_FAILED(502). 429·5xx·타임아웃은 maxRetries만큼 다시 시도한다.
 * 프롬프트와 응답 본문은 로그에 남기지 않는다. (README 로그 규칙)
 */
@Slf4j
@Component
public class GeminiClient {

    /** batchEmbedContents 한 번에 보낼 수 있는 최대 개수 */
    static final int EMBED_BATCH_SIZE = 100;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final JsonMapper jsonMapper;
    private final long retryBackoffMillis;

    @Autowired
    public GeminiClient(GeminiProperties properties, JsonMapper jsonMapper) {
        this(buildRestClient(properties), properties, jsonMapper, 1000);
    }

    GeminiClient(RestClient restClient, GeminiProperties properties, JsonMapper jsonMapper, long retryBackoffMillis) {
        this.restClient = restClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** 텍스트 생성 (논의 분석, 후보 추출) */
    public String generate(String prompt) {
        return generateText(prompt, false);
    }

    /**
     * JSON으로 응답받아 객체로 변환한다. 형식이 맞지 않으면 AI_CALL_FAILED. (README 12번)
     * 응답 JSON에 type에 없는 필드가 있어도 무시한다.
     */
    public <T> T generateJson(String prompt, Class<T> type) {
        String text = generateText(prompt, true);
        try {
            return jsonMapper.readValue(stripCodeFence(text), type);
        } catch (JacksonException e) {
            log.warn("Gemini JSON 응답 형식 오류: type={}", type.getSimpleName());
            throw new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
    }

    /** 임베딩 벡터 */
    public float[] embed(String text) {
        Map<String, Object> body = Map.of(
                "model", "models/" + properties.embeddingModel(),
                "content", Map.of("parts", List.of(Map.of("text", text))));
        Map<String, Object> json = call("embedContent", () -> post(properties.embeddingModel() + ":embedContent", body));
        return toVector(obj(json, "embedding"));
    }

    /** 여러 텍스트의 임베딩. 결과 순서는 texts와 같다. */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += EMBED_BATCH_SIZE) {
            List<String> chunk = texts.subList(from, Math.min(from + EMBED_BATCH_SIZE, texts.size()));
            Map<String, Object> body = Map.of("requests", chunk.stream()
                    .map(text -> Map.of(
                            "model", "models/" + properties.embeddingModel(),
                            "content", Map.of("parts", List.of(Map.of("text", text)))))
                    .toList());
            Map<String, Object> json = call("batchEmbedContents",
                    () -> post(properties.embeddingModel() + ":batchEmbedContents", body));
            List<Map<String, Object>> embeddings = list(json, "embeddings");
            if (embeddings.size() != chunk.size()) {
                log.warn("Gemini 임베딩 개수 불일치: requested={}, received={}", chunk.size(), embeddings.size());
                throw new BusinessException(ErrorCode.AI_CALL_FAILED);
            }
            embeddings.stream().map(GeminiClient::toVector).forEach(vectors::add);
        }
        return vectors;
    }

    // ===================== 내부 =====================

    private String generateText(String prompt, boolean json) {
        Map<String, Object> generationConfig = json
                ? Map.of("responseMimeType", "application/json", "temperature", 0.2)
                : Map.of("temperature", 0.4);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", generationConfig);
        Map<String, Object> response = call("generateContent",
                () -> post(properties.model() + ":generateContent", body));
        List<Map<String, Object>> candidates = list(response, "candidates");
        if (candidates.isEmpty()) {
            // 안전 필터 등으로 응답이 막힌 경우
            log.warn("Gemini 응답 없음: blockReason={}", str(obj(response, "promptFeedback"), "blockReason"));
            throw new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
        String text = list(obj(candidates.getFirst(), "content"), "parts").stream()
                .map(part -> str(part, "text"))
                .filter(t -> t != null)
                .collect(Collectors.joining());
        if (text.isBlank()) {
            log.warn("Gemini 빈 응답: finishReason={}", str(candidates.getFirst(), "finishReason"));
            throw new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
        return text;
    }

    private Map<String, Object> post(String modelAction, Map<String, Object> body) {
        return restClient.post()
                .uri(properties.baseUrl() + "/models/" + modelAction)
                .header("x-goog-api-key", properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JSON);
    }

    /**
     * 재시도를 포함한 전체 시간이 timeoutSeconds(기본 30초)를 넘지 않게 한다. (논의 분석 30초 목표)
     * 한 번의 호출은 attemptTimeout(전체의 2/3)까지 기다리고, 남은 시간 안에 한 번 더 호출할 수 있을 때만 다시 시도한다.
     */
    private Map<String, Object> call(String action, Supplier<Map<String, Object>> request) {
        if (!isConfigured()) {
            log.warn("Gemini API 키(GEMINI_API_KEY)가 설정되지 않았습니다.");
            throw new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
        long deadline = System.currentTimeMillis() + properties.timeoutSeconds() * 1000L;
        long attemptMillis = attemptTimeout(properties).toMillis();
        for (int attempt = 0; ; attempt++) {
            String failure;
            try {
                return request.get();
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (status != 429 && status < 500) {
                    log.warn("Gemini {} 실패: status={}", action, status);
                    throw new BusinessException(ErrorCode.AI_CALL_FAILED);
                }
                failure = "status=" + status;
            } catch (RestClientException e) {
                failure = e.getClass().getSimpleName();
            }
            long backoff = retryBackoffMillis * (attempt + 1);
            boolean timeLeft = System.currentTimeMillis() + backoff + attemptMillis <= deadline;
            if (attempt >= properties.maxRetries() || !timeLeft) {
                log.warn("Gemini {} 실패: {}, attempt={}", action, failure, attempt + 1);
                throw new BusinessException(ErrorCode.AI_CALL_FAILED);
            }
            sleep(backoff);
        }
    }

    /** 한 번의 호출에 기다리는 시간: 전체 시간의 2/3 (최소 5초) */
    static Duration attemptTimeout(GeminiProperties properties) {
        return Duration.ofSeconds(Math.max(5, properties.timeoutSeconds() * 2L / 3));
    }

    private static float[] toVector(Map<String, Object> embedding) {
        Object values = embedding.get("values");
        if (!(values instanceof List<?> numbers) || numbers.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
        float[] vector = new float[numbers.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = ((Number) numbers.get(i)).floatValue();
        }
        return vector;
    }

    /** responseMimeType을 지정해도 ```json ... ``` 으로 감싸서 줄 때가 있다. */
    static String stripCodeFence(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineEnd > 0 && lastFence > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
    }

    private static RestClient buildRestClient(GeminiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(attemptTimeout(properties));
        return RestClient.builder().requestFactory(factory).build();
    }
}
