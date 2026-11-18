package com.togather.content.notion.service;

import static com.togather.common.util.JsonValues.str;

import com.togather.common.enums.Provider;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncService;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Notion 웹훅으로 받은 페이지 · 데이터베이스 변경을 바로 동기화한다. (5분 주기 동기화를 기다리지 않음)
 *
 * <p>이벤트에는 바뀐 내용이 들어 있지 않으므로, 이벤트의 Notion 워크스페이스를 연동한 ToGather 워크스페이스의
 * Notion 연동 대상을 즉시 동기화한다. 바뀐 게 있으면 동기화가 보고 있는 화면에 알린다.
 * Notion은 짧은 시간에 이벤트를 여러 번 보내므로, 같은 워크스페이스가 이미 대기 중이면 한 번만 동기화한다.
 */
@Slf4j
@Service
public class NotionWebhookService {

    private final String verificationToken;
    private final IntegrationService integrationService;
    private final SyncService syncService;
    /** 동기화를 기다리는 ToGather 워크스페이스 (같은 워크스페이스 이벤트가 몰려오면 한 번만) */
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notion-webhook");
        t.setDaemon(true);
        return t;
    });

    public NotionWebhookService(@Value("${togather.notion.webhook-token:}") String verificationToken,
                                IntegrationService integrationService, SyncService syncService) {
        this.verificationToken = verificationToken;
        this.integrationService = integrationService;
        this.syncService = syncService;
    }

    public boolean isConfigured() {
        return verificationToken != null && !verificationToken.isBlank();
    }

    /** X-Notion-Signature 검증: sha256=HMAC-SHA256(verification_token, 본문) */
    public boolean verify(String signature, String rawBody) {
        if (!isConfigured() || signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(verificationToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** 이벤트 한 건을 뒤에서 처리한다. 페이지 · 데이터베이스 이벤트만 본다. (댓글 등은 무시) */
    public void handleAsync(Map<String, Object> event) {
        String type = str(event, "type");
        if (type == null || !(type.startsWith("page.") || type.startsWith("database.") || type.startsWith("data_source."))) {
            return;
        }
        for (Long workspaceId : findWorkspaces(event)) {
            if (pending.add(workspaceId)) {
                executor.submit(() -> {
                    pending.remove(workspaceId);
                    syncWorkspace(workspaceId);
                });
            }
        }
    }

    List<Long> findWorkspaces(Map<String, Object> event) {
        String notionWorkspaceId = str(event, "workspace_id");
        if (notionWorkspaceId == null) {
            return List.of();
        }
        return integrationService.findWorkspacesByExternalAccount(Provider.NOTION, notionWorkspaceId);
    }

    void syncWorkspace(Long workspaceId) {
        try {
            for (String targetId : integrationService.getConnectionTargets(workspaceId, Provider.NOTION)) {
                syncService.syncTargetNow(workspaceId, Provider.NOTION, targetId);
            }
        } catch (RuntimeException e) {
            log.warn("Notion 웹훅 동기화 실패: workspaceId={}, error={}", workspaceId, e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
