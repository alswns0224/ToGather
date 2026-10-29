package com.togather.content.slack.controller;

import com.togather.content.slack.service.SlackEventService;
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
 * Slack Events API 수신 주소. Slack 앱 설정의 Event Subscriptions → Request URL에 {APP_URL}/api/slack/events를 넣는다.
 * 로그인 없이 Slack이 직접 부르므로, 서명(X-Slack-Signature)으로 Slack이 보낸 요청인지 확인한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SlackEventController {

    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {
    };

    private final SlackEventService slackEventService;
    private final JsonMapper jsonMapper;

    @PostMapping("/api/slack/events")
    public ResponseEntity<?> receive(@RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
                                     @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
                                     @RequestHeader(value = "X-Slack-Retry-Num", required = false) String retryNum,
                                     @RequestBody String rawBody) {
        if (!slackEventService.isConfigured()) {
            log.warn("Slack 이벤트를 받았지만 SLACK_SIGNING_SECRET이 설정되지 않았습니다.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!slackEventService.verify(timestamp, signature, rawBody)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> payload = jsonMapper.readValue(rawBody, JSON);
        if ("url_verification".equals(payload.get("type"))) {
            // 앱 설정에서 Request URL을 저장할 때 Slack이 주소를 확인한다.
            return ResponseEntity.ok(Map.of("challenge", String.valueOf(payload.get("challenge"))));
        }
        // 처리가 늦어 Slack이 다시 보낸 요청은 이미 받은 것이므로 무시한다.
        if (retryNum == null && "event_callback".equals(payload.get("type"))) {
            slackEventService.handleAsync(payload);
        }
        return ResponseEntity.ok().build();
    }
}
