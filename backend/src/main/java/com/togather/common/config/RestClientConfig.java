package com.togather.common.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 외부 API 호출용 RestClient. 연결 5초, 응답 10초 타임아웃 (README 백엔드 코드 규칙).
 * JDK HttpClient를 쓴다. (HttpURLConnection 기반 SimpleClientHttpRequestFactory는 PATCH를 보내지 못해
 * Google Calendar 일정 수정 · Notion 페이지 수정이 실패한다)
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient externalRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }
}
