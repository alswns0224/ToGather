package com.togather.content.notion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Notion 웹훅: 서명 확인, 이벤트 → 그 Notion 워크스페이스를 연동한 곳의 대상 즉시 동기화 */
class NotionWebhookServiceTest {

    private static final String TOKEN = "secret_test";

    private IntegrationService integrationService;
    private SyncService syncService;
    private NotionWebhookService service;

    @BeforeEach
    void setUp() {
        integrationService = mock(IntegrationService.class);
        syncService = mock(SyncService.class);
        service = new NotionWebhookService(TOKEN, integrationService, syncService);
    }

    @Test
    void 서명이_맞을_때만_통과한다() throws Exception {
        String body = "{\"type\":\"page.content_updated\"}";
        assertThat(service.verify(sign(body), body)).isTrue();
        assertThat(service.verify(sign(body), body + " ")).isFalse();
        assertThat(service.verify(null, body)).isFalse();
        assertThat(new NotionWebhookService("", integrationService, syncService).verify(sign(body), body)).isFalse();
    }

    @Test
    void 페이지_이벤트면_연동한_워크스페이스의_Notion_대상을_바로_동기화한다() {
        when(integrationService.findWorkspacesByExternalAccount(Provider.NOTION, "nw-1")).thenReturn(List.of(10L));
        when(integrationService.getConnectionTargets(10L, Provider.NOTION)).thenReturn(List.of("db-1", "page:p-1"));

        service.handleAsync(Map.of("type", "page.content_updated", "workspace_id", "nw-1"));

        verify(syncService, timeout(2000)).syncTargetNow(10L, Provider.NOTION, "db-1");
        verify(syncService, timeout(2000)).syncTargetNow(10L, Provider.NOTION, "page:p-1");
    }

    @Test
    void 페이지를_지우면_그_페이지_연동_대상을_뺀다() {
        service.handleAsync(Map.of("type", "page.deleted", "workspace_id", "nw-1",
                "entity", Map.of("id", "p-1", "type", "page")));
        service.handleAsync(Map.of("type", "database.deleted", "workspace_id", "nw-1",
                "entity", Map.of("id", "db-1", "type", "database")));

        verify(integrationService).removeDeletedTargetsByExternalAccount(Provider.NOTION, "nw-1", List.of("page:p-1"));
        verify(integrationService).removeDeletedTargetsByExternalAccount(Provider.NOTION, "nw-1", List.of("db-1"));
    }

    @Test
    void 페이지_수정_이벤트로는_연동_대상을_빼지_않는다() {
        service.handleAsync(Map.of("type", "page.content_updated", "workspace_id", "nw-1",
                "entity", Map.of("id", "p-1", "type", "page")));

        verify(integrationService, never()).removeDeletedTargetsByExternalAccount(any(), any(), any());
    }

    @Test
    void 댓글_이벤트나_연동하지_않은_워크스페이스는_무시한다() {
        service.handleAsync(Map.of("type", "comment.created", "workspace_id", "nw-1"));
        when(integrationService.findWorkspacesByExternalAccount(Provider.NOTION, "nw-x")).thenReturn(List.of());
        service.handleAsync(Map.of("type", "page.created", "workspace_id", "nw-x"));

        verifyNoInteractions(syncService);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
