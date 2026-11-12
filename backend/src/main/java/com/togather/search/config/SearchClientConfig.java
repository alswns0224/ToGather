package com.togather.search.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Ollama·Chroma 호출용 RestClient.
 * Ollama는 모델을 처음 불러올 때 수십 초가 걸릴 수 있어 외부 API용(응답 10초)보다 길게 기다린다.
 */
@Configuration
public class SearchClientConfig {

    @Bean
    public RestClient searchRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory).build();
    }
}
