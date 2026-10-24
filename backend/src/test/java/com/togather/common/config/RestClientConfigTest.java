package com.togather.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RestClientConfigTest {

    @Test
    void 외부_API_호출용_RestClient는_PATCH를_보낼_수_있다() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String result = new RestClientConfig().externalRestClient().patch()
                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/events/1")
                    .body("{\"summary\":\"x\"}")
                    .retrieve()
                    .body(String.class);

            assertThat(method.get()).isEqualTo("PATCH");
            assertThat(result).isEqualTo("{}");
        } finally {
            server.stop(0);
        }
    }
}
