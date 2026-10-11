package com.togather.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class GeminiClientTest {

    private static final String BASE = "https://gemini.test/v1beta";

    private MockRestServiceServer server;
    private GeminiClient client;

    record Decision(String content, List<String> evidence) {
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GeminiProperties properties = new GeminiProperties("test-key", BASE, "gen-model", "embed-model", 30, 2);
        JsonMapper mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        client = new GeminiClient(builder.build(), properties, mapper, 0);
    }

    @Test
    void 텍스트를_생성한다() {
        server.expect(requestTo(BASE + "/models/gen-model:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("안녕"))
                .andRespond(withSuccess(textResponse("반갑"), MediaType.APPLICATION_JSON));

        assertThat(client.generate("안녕")).isEqualTo("반갑");
    }

    @Test
    void JSON_응답을_객체로_바꾸고_코드블록과_모르는_필드는_무시한다() {
        String json = "```json\\n{\\\"content\\\": \\\"A안으로 결정\\\", \\\"evidence\\\": [\\\"1.0\\\"], \\\"extra\\\": 1}\\n```";
        server.expect(requestTo(BASE + "/models/gen-model:generateContent"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andRespond(withSuccess(textResponse(json), MediaType.APPLICATION_JSON));

        Decision decision = client.generateJson("분석해줘", Decision.class);

        assertThat(decision.content()).isEqualTo("A안으로 결정");
        assertThat(decision.evidence()).containsExactly("1.0");
    }

    @Test
    void JSON_형식이_아니면_AI_CALL_FAILED() {
        server.expect(requestTo(BASE + "/models/gen-model:generateContent"))
                .andRespond(withSuccess(textResponse("결정사항은 없습니다"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generateJson("분석해줘", Decision.class))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_CALL_FAILED));
    }

    @Test
    void 서버_오류는_다시_시도한다() {
        server.expect(times(2), requestTo(BASE + "/models/gen-model:generateContent")).andRespond(withServerError());
        server.expect(requestTo(BASE + "/models/gen-model:generateContent"))
                .andRespond(withSuccess(textResponse("성공"), MediaType.APPLICATION_JSON));

        assertThat(client.generate("안녕")).isEqualTo("성공");
        server.verify();
    }

    @Test
    void 남은_시간이_없으면_다시_시도하지_않는다() {
        // 전체 6초, 한 번 호출 5초(최소값). 백오프 2초면 다시 시도할 시간이 없다.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer tight = MockRestServiceServer.bindTo(builder).build();
        GeminiClient shortBudget = new GeminiClient(builder.build(),
                new GeminiProperties("test-key", BASE, "gen-model", "embed-model", 6, 2),
                JsonMapper.builder().build(), 2000);
        tight.expect(times(1), requestTo(BASE + "/models/gen-model:generateContent")).andRespond(withServerError());

        assertThatThrownBy(() -> shortBudget.generate("안녕")).isInstanceOf(BusinessException.class);
        tight.verify();
    }

    @Test
    void 잘못된_요청은_다시_시도하지_않는다() {
        server.expect(times(1), requestTo(BASE + "/models/gen-model:generateContent")).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.generate("안녕")).isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test
    void 임베딩_벡터를_받는다() {
        server.expect(requestTo(BASE + "/models/embed-model:embedContent"))
                .andExpect(jsonPath("$.model").value("models/embed-model"))
                .andRespond(withSuccess("{\"embedding\": {\"values\": [0.1, -0.2, 0.3]}}", MediaType.APPLICATION_JSON));

        assertThat(client.embed("검색어")).containsExactly(0.1f, -0.2f, 0.3f);
    }

    @Test
    void 여러_텍스트를_한_번에_임베딩한다() {
        server.expect(requestTo(BASE + "/models/embed-model:batchEmbedContents"))
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andRespond(withSuccess("{\"embeddings\": [{\"values\": [1.0]}, {\"values\": [2.0]}]}",
                        MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embedAll(List.of("a", "b"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(1)).containsExactly(2.0f);
    }

    @Test
    void API_키가_없으면_호출하지_않고_실패한다() {
        GeminiClient noKey = new GeminiClient(RestClient.create(),
                new GeminiProperties("", BASE, null, null, null, null), JsonMapper.builder().build(), 0);

        assertThatThrownBy(() -> noKey.generate("안녕")).isInstanceOf(BusinessException.class);
    }

    private static String textResponse(String text) {
        return "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" + text + "\"}]}, \"finishReason\": \"STOP\"}]}";
    }
}
