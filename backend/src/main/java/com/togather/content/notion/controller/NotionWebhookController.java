package com.togather.content.notion.controller;

import com.togather.content.notion.service.NotionWebhookService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notion 웹훅 수신 주소. Notion 연동 설정의 Webhooks → Webhook URL에 {APP_URL}/api/notion/webhook을 넣는다.
 *
 * <ol>
 *   <li>구독을 만들면 Notion이 verification_token을 한 번 보낸다. 서버 로그에 찍히므로 그 값을 Notion의 Verify 칸과
 *       .env의 NOTION_WEBHOOK_TOKEN에 넣고 서버를 다시 켠다.</li>
 *   <li>그 뒤 이벤트는 서명(X-Notion-Signature)으로 Notion이 보낸 요청인지 확인한다.</li>
 * </ol>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class NotionWebhookController {

    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {
    };

    private final NotionWebhookService notionWebhookService;
    private final JsonMapper jsonMapper;

    @PostMapping("/api/notion/webhook")
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Notion-Signature", required = false) String signature,
                                        @RequestBody String rawBody) {
        Map<String, Object> payload = jsonMapper.readValue(rawBody, JSON);
        if (payload.get("verification_token") instanceof String token && payload.get("type") == null) {
            // 구독 확인 요청: 이 값을 Notion 화면의 Verify 칸과 NOTION_WEBHOOK_TOKEN에 넣는다. (로컬 개발 로그에만 찍힌다)
            log.info("Notion 웹훅 확인 토큰을 받았습니다. Notion Verify 칸과 .env NOTION_WEBHOOK_TOKEN에 넣으세요: {}", token);
            return ResponseEntity.ok().build();
        }
        if (!notionWebhookService.isConfigured()) {
            log.warn("Notion 웹훅 이벤트를 받았지만 NOTION_WEBHOOK_TOKEN이 설정되지 않았습니다.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!notionWebhookService.verify(signature, rawBody)) {
            log.warn("Notion 웹훅 서명이 맞지 않아 거절했습니다. NOTION_WEBHOOK_TOKEN이 구독의 토큰과 같은지 확인하세요.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("Notion 웹훅 이벤트: type={}", payload.get("type"));
        notionWebhookService.handleAsync(payload);
        return ResponseEntity.ok().build();
    }
}
