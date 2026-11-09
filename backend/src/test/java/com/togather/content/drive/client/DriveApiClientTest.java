package com.togather.content.drive.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;

class DriveApiClientTest {

    @Test
    void 다운로드는_메타데이터_제한_시간이_지난_후에도_응답_본문을_끝까지_읽는다() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/drive/v3/files/test-file", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 2);
                var body = exchange.getResponseBody();
                body.write(1);
                body.flush();
                try {
                    Thread.sleep(Duration.ofSeconds(1));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                body.write(2);
            }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            JdkClientHttpRequestFactory metadataFactory = new JdkClientHttpRequestFactory();
            metadataFactory.setReadTimeout(Duration.ofMillis(300));
            RestClient metadataClient = RestClient.builder()
                    .requestFactory(metadataFactory)
                    .requestInterceptor((request, body, execution) -> execution.execute(
                            new HttpRequestWrapper(request) {
                                @Override
                                public URI getURI() {
                                    return URI.create(origin + super.getURI().getRawPath() + "?"
                                            + super.getURI().getRawQuery());
                                }
                            }, body))
                    .build();
            DriveApiClient client = new DriveApiClient(metadataClient, Duration.ofSeconds(10));
            ByteArrayOutputStream downloaded = new ByteArrayOutputStream();

            client.streamFile("test-token", "test-file", "application/octet-stream", downloaded);

            assertThat(downloaded.toByteArray()).containsExactly((byte) 1, (byte) 2);
        } finally {
            server.stop(0);
        }
    }
}
