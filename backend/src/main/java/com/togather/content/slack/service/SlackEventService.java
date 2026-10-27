package com.togather.content.slack.service;

import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.content.service.ContentRefService;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncService;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Slack Events API로 받은 메시지 작성 · 수정 · 삭제를 바로 반영하고, 보고 있는 화면에 알린다.
 *
 * <ul>
 *   <li>새 메시지 · 답글: 그 채널만 바로 동기화한다. (작성자 이름 · 링크 · 답글 처리를 기존 동기화와 똑같이 하기 위해)</li>
 *   <li>수정: 저장된 메시지 본문만 바꾼다. 저장된 적 없으면 동기화로 가져온다.</li>
 *   <li>삭제: 원본 삭제로 표시한다. (근거 발언 · Task 연결에서는 보관된 사본을 쓴다)</li>
 * </ul>
 * Slack은 3초 안에 응답하지 않으면 다시 보내므로, 요청은 바로 받고 처리는 별도 스레드에서 한다.
 */
@Slf4j
@Service
public class SlackEventService {

    /** 서명 시각이 이보다 오래된 요청은 재전송 공격으로 보고 버린다. (Slack 권장 5분) */
    private static final long MAX_AGE_SECONDS = 5 * 60;

    private final String signingSecret;
    private final IntegrationService integrationService;
    private final SyncService syncService;
    private final SlackContentService slackContentService;
    private final WorkspaceEventService workspaceEventService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "slack-events");
        t.setDaemon(true);
        return t;
    });

    public SlackEventService(@Value("${togather.slack.signing-secret:}") String signingSecret,
                             IntegrationService integrationService, SyncService syncService,
                             SlackContentService slackContentService, WorkspaceEventService workspaceEventService) {
        this.signingSecret = signingSecret;
        this.integrationService = integrationService;
        this.syncService = syncService;
        this.slackContentService = slackContentService;
        this.workspaceEventService = workspaceEventService;
    }

    public boolean isConfigured() {
        return signingSecret != null && !signingSecret.isBlank();
    }

    /** X-Slack-Signature 검증: v0=HMAC-SHA256(signing secret, "v0:{timestamp}:{본문}") */
    public boolean verify(String timestamp, String signature, String rawBody) {
        if (!isConfigured() || timestamp == null || signature == null) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - ts) > MAX_AGE_SECONDS) {
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "v0=" + HexFormat.of().formatHex(
                    mac.doFinal(("v0:" + timestamp + ":" + rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** event_callback 한 건을 뒤에서 처리한다. */
    public void handleAsync(Map<String, Object> payload) {
        Map<String, Object> event = obj(payload, "event");
        if (!"message".equals(str(event, "type"))) {
            return;
        }
        executor.submit(() -> {
            try {
                handleMessage(event);
            } catch (RuntimeException e) {
                log.warn("Slack 이벤트 처리 실패: {}", e.getClass().getSimpleName());
            }
        });
    }

    void handleMessage(Map<String, Object> event) {
        String channelId = str(event, "channel");
        if (channelId == null) {
            return;
        }
        List<Long> workspaceIds = integrationService.findWorkspacesByTarget(Provider.SLACK, channelId);
        if (workspaceIds.isEmpty()) {
            return;
        }
        String subtype = str(event, "subtype");
        for (Long workspaceId : workspaceIds) {
            if ("message_deleted".equals(subtype)) {
                int changed = ContentRefService.countChanges(() ->
                        slackContentService.applyRemoteDelete(workspaceId, channelId, str(event, "deleted_ts")));
                publishIfChanged(workspaceId, channelId, changed);
            } else if ("message_changed".equals(subtype)) {
                Map<String, Object> message = obj(event, "message");
                String ts = str(message, "ts");
                boolean[] applied = {false};
                int changed = ContentRefService.countChanges(() -> applied[0] = ts != null
                        && slackContentService.applyRemoteEdit(workspaceId, channelId, ts, str(message, "text"),
                                SlackApiClient.toLocal(str(obj(message, "edited"), "ts"))));
                if (applied[0]) {
                    // ToGather에서 고친 메시지면 이미 같은 내용이라 알리지 않는다.
                    publishIfChanged(workspaceId, channelId, changed);
                } else {
                    // 저장된 적 없는 메시지: 동기화로 가져온다. (바뀐 게 있으면 동기화가 화면에 알린다)
                    syncService.syncTargetNow(workspaceId, Provider.SLACK, channelId);
                }
            } else {
                // 새 메시지 · 답글 · 파일 공유 · 채널에도 보낸 답글.
                // ToGather에서 쓴 메시지는 이미 저장돼 있으므로 다시 동기화하지 않는다.
                String ts = str(event, "ts");
                if (ts != null && slackContentService.isStored(workspaceId, channelId, ts)) {
                    continue;
                }
                syncService.syncTargetNow(workspaceId, Provider.SLACK, channelId);
            }
        }
    }

    private void publishIfChanged(Long workspaceId, String channelId, int changed) {
        if (changed > 0) {
            workspaceEventService.publish(workspaceId, Provider.SLACK.name(), channelId);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
