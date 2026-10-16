package com.togather.content.gmail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.gmail.client.GmailApiClient;
import com.togather.content.gmail.client.GmailApiClient.GmailMessage;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter.SyncContext;
import com.togather.sync.service.SyncAdapter.SyncTarget;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GmailSyncAdapterTest {

    @Mock
    private GmailApiClient gmailApiClient;

    @Mock
    private ContentRefService contentRefService;

    @Mock
    private IntegrationService integrationService;

    private GmailSyncAdapter gmailSyncAdapter;

    @BeforeEach
    void setUp() {
        gmailSyncAdapter = new GmailSyncAdapter(
            gmailApiClient,
            contentRefService,
            integrationService
        );
    }

    @Test
    void provider_returnsGmail() {
        assertEquals(
            Provider.GMAIL,
            gmailSyncAdapter.provider()
        );
    }

    @Test
    void findTargets_returnsPersonalTargetWhenConnected() {
        Long workspaceId = 1L;
        Long userId = 10L;

        when(integrationService.hasConnection(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(true);

        List<SyncTarget> targets =
            gmailSyncAdapter.findTargets(
                workspaceId,
                userId
            );

        assertEquals(1, targets.size());
        assertEquals("", targets.get(0).targetId());
        assertEquals(userId, targets.get(0).ownerUserId());
    }

    @Test
    void findTargets_returnsEmptyWhenNotConnected() {
        Long workspaceId = 1L;
        Long userId = 10L;

        when(integrationService.hasConnection(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(false);

        List<SyncTarget> targets =
            gmailSyncAdapter.findTargets(
                workspaceId,
                userId
            );

        assertTrue(targets.isEmpty());
    }

    @Test
    void findTargets_returnsEmptyForSchedulerRequest() {
        List<SyncTarget> targets =
            gmailSyncAdapter.findTargets(
                1L,
                null
            );

        assertTrue(targets.isEmpty());

        verify(
            integrationService,
            never()
        ).hasConnection(
            any(),
            any(),
            any()
        );
    }

    @Test
    void sync_firstSyncLoadsRecentMessagesAndSavesAsMail() {
        Long workspaceId = 1L;
        Long userId = 10L;
        String token = "test-token";

        LocalDateTime createdAt =
            LocalDateTime.of(
                2026,
                10,
                17,
                10,
                0
            );

        GmailMessage message =
            new GmailMessage(
                "message-1",
                "thread-1",
                "history-1",
                "테스트 메일",
                "sender@example.com",
                "receiver@example.com",
                "",
                "메일 미리보기",
                createdAt,
                "https://mail.google.com/mail/u/0/#all/message-1"
            );

        when(integrationService.getAccessToken(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(token);

        when(gmailApiClient.listMessageIds(
            eq(token),
            any(LocalDateTime.class),
            eq(10)
        )).thenReturn(
            List.of("message-1")
        );

        when(gmailApiClient.getMessage(
            token,
            "message-1"
        )).thenReturn(message);

        when(contentRefService.upsert(
            any(ContentRefUpsert.class)
        )).thenReturn(
            new ContentRefDTO(
                100L,
                Provider.GMAIL,
                ContentType.MAIL,
                "message-1",
                "테스트 메일",
                "메일 미리보기",
                message.deepLink(),
                null,
                createdAt,
                0,
                false
            )
        );

        SyncContext context =
            new SyncContext(
                workspaceId,
                "",
                userId,
                null,
                userId
            );

        var result =
            gmailSyncAdapter.sync(context);

        assertEquals(
            1,
            result.savedCount()
        );

        ArgumentCaptor<LocalDateTime> afterCaptor =
            ArgumentCaptor.forClass(
                LocalDateTime.class
            );

        verify(gmailApiClient).listMessageIds(
            eq(token),
            afterCaptor.capture(),
            eq(10)
        );

        LocalDateTime now =
            LocalDateTime.now(
                GmailApiClient.ZONE
            );

        assertTrue(
            afterCaptor.getValue()
                .isAfter(
                    now.minusDays(31)
                )
        );

        assertTrue(
            afterCaptor.getValue()
                .isBefore(
                    now.minusDays(29)
                )
        );

        ArgumentCaptor<ContentRefUpsert> upsertCaptor =
            ArgumentCaptor.forClass(
                ContentRefUpsert.class
            );

        verify(contentRefService).upsert(
            upsertCaptor.capture()
        );

        ContentRefUpsert command =
            upsertCaptor.getValue();

        assertEquals(
            workspaceId,
            command.workspaceId()
        );

        assertEquals(
            userId,
            command.ownerUserId()
        );

        assertEquals(
            Provider.GMAIL,
            command.provider()
        );

        assertEquals(
            ContentType.MAIL,
            command.contentType()
        );

        assertEquals(
            "message-1",
            command.originId()
        );

        assertEquals(
            "테스트 메일",
            command.title()
        );

        // content가 비어 있으므로 snippet을 저장해야 한다.
        assertEquals(
            "메일 미리보기",
            command.content()
        );

        assertEquals(
            "sender@example.com",
            command.authorName()
        );
    }

    @Test
    void sync_incrementalSyncUsesFiveMinuteLookback() {
        Long workspaceId = 1L;
        Long userId = 10L;
        String token = "test-token";

        LocalDateTime since =
            LocalDateTime.of(
                2026,
                10,
                17,
                12,
                0
            );

        when(integrationService.getAccessToken(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(token);

        when(gmailApiClient.listMessageIds(
            token,
            since.minusMinutes(5),
            5
        )).thenReturn(List.of());

        SyncContext context =
            new SyncContext(
                workspaceId,
                "",
                userId,
                since,
                userId
            );

        var result =
            gmailSyncAdapter.sync(context);

        assertEquals(
            0,
            result.savedCount()
        );

        verify(gmailApiClient).listMessageIds(
            token,
            since.minusMinutes(5),
            5
        );
    }

    @Test
    void sync_throwsWhenOwnerUserIdIsMissing() {
        SyncContext context =
            new SyncContext(
                1L,
                "",
                null,
                null,
                null
            );

        assertThrows(
            BusinessException.class,
            () -> gmailSyncAdapter.sync(context)
        );
    }

    @Test
    void sync_marksFailedWhenContentSaveFails() {
        Long workspaceId = 1L;
        Long userId = 10L;
        String token = "test-token";

        GmailMessage message =
            new GmailMessage(
                "message-1",
                "thread-1",
                "history-1",
                "테스트 메일",
                "sender@example.com",
                "receiver@example.com",
                "본문",
                "미리보기",
                LocalDateTime.of(
                    2026,
                    10,
                    17,
                    10,
                    0
                ),
                "https://mail.google.com/mail/u/0/#all/message-1"
            );

        when(integrationService.getAccessToken(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(token);

        when(gmailApiClient.listMessageIds(
            eq(token),
            any(LocalDateTime.class),
            eq(10)
        )).thenReturn(
            List.of("message-1")
        );

        when(gmailApiClient.getMessage(
            token,
            "message-1"
        )).thenReturn(message);

        when(contentRefService.upsert(
            any(ContentRefUpsert.class)
        )).thenThrow(
            new RuntimeException(
                "DB error"
            )
        );

        SyncContext context =
            new SyncContext(
                workspaceId,
                "",
                userId,
                null,
                userId
            );

        var result =
            gmailSyncAdapter.sync(context);

        assertEquals(
            0,
            result.savedCount()
        );

        verify(contentRefService)
            .markSyncFailed(
                workspaceId,
                Provider.GMAIL,
                "message-1"
            );

        assertFalse(
            result.syncedUntil() == null
        );
    }
}
