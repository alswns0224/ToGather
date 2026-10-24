package com.togather.content.gmail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.togather.content.gmail.client.GmailApiClient.GmailProfile;
import com.togather.content.gmail.client.GmailApiClient.HistoryResult;
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
        assertEquals(
            userId,
            targets.get(0).ownerUserId()
        );
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

        when(gmailApiClient.getProfile(token))
            .thenReturn(
                new GmailProfile(
                    "user@example.com",
                    "1000"
                )
            );

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
                null,
                userId
            );

        var result =
            gmailSyncAdapter.sync(context);

        assertEquals(
            1,
            result.savedCount()
        );

        assertEquals(
            "1000",
            result.syncCursor()
        );

        assertNotNull(
            result.syncedUntil()
        );

        verify(gmailApiClient)
            .getProfile(token);

        ArgumentCaptor<LocalDateTime> afterCaptor =
            ArgumentCaptor.forClass(
                LocalDateTime.class
            );

        verify(gmailApiClient)
            .listMessageIds(
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

        verify(contentRefService)
            .upsert(
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

        // 본문이 비어 있으므로 snippet을 저장한다.
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
    void sync_incrementalSyncUsesHistoryId() {
        Long workspaceId = 1L;
        Long userId = 10L;
        String token = "test-token";

        String previousHistoryId = "1000";
        String latestHistoryId = "1050";

        LocalDateTime createdAt =
            LocalDateTime.of(
                2026,
                10,
                17,
                12,
                0
            );

        GmailMessage message =
            new GmailMessage(
                "message-2",
                "thread-2",
                latestHistoryId,
                "증분 메일",
                "sender@example.com",
                "receiver@example.com",
                "증분 본문",
                "증분 미리보기",
                createdAt,
                "https://mail.google.com/mail/u/0/#all/message-2"
            );

        when(integrationService.getAccessToken(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(token);

        when(gmailApiClient.listHistory(
            token,
            previousHistoryId,
            5
        )).thenReturn(
            new HistoryResult(
                List.of("message-2"),
                List.of(),
                latestHistoryId
            )
        );

        when(gmailApiClient.getMessage(
            token,
            "message-2"
        )).thenReturn(message);

        when(contentRefService.upsert(
            any(ContentRefUpsert.class)
        )).thenReturn(
            new ContentRefDTO(
                101L,
                Provider.GMAIL,
                ContentType.MAIL,
                "message-2",
                "증분 메일",
                "증분 본문",
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
                previousHistoryId,
                userId
            );

        var result =
            gmailSyncAdapter.sync(context);

        assertEquals(
            1,
            result.savedCount()
        );

        assertEquals(
            latestHistoryId,
            result.syncCursor()
        );

        assertNotNull(
            result.syncedUntil()
        );

        verify(gmailApiClient)
            .listHistory(
                token,
                previousHistoryId,
                5
            );

        verify(
            gmailApiClient,
            never()
        ).listMessageIds(
            any(String.class),
            any(LocalDateTime.class),
            anyInt()
        );

        verify(
            gmailApiClient,
            never()
        ).getProfile(
            any(String.class)
        );
    }

    @Test
    void sync_incrementalSyncKeepsPreviousCursorWhenLatestCursorIsMissing() {
        Long workspaceId = 1L;
        Long userId = 10L;
        String token = "test-token";

        String previousHistoryId = "1000";

        when(integrationService.getAccessToken(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(token);

        when(gmailApiClient.listHistory(
            token,
            previousHistoryId,
            5
        )).thenReturn(
            new HistoryResult(
                List.of(),
                List.of(),
                null
            )
        );

        SyncContext context =
            new SyncContext(
                workspaceId,
                "",
                userId,
                null,
                previousHistoryId,
                userId
            );

        var result =
            gmailSyncAdapter.sync(context);

        assertEquals(
            0,
            result.savedCount()
        );

        assertEquals(
            previousHistoryId,
            result.syncCursor()
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
                "본문",
                "미리보기",
                createdAt,
                "https://mail.google.com/mail/u/0/#all/message-1"
            );

        when(integrationService.getAccessToken(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(token);

        when(gmailApiClient.getProfile(token))
            .thenReturn(
                new GmailProfile(
                    "user@example.com",
                    "1000"
                )
            );

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
                null,
                userId
            );

        var result =
            gmailSyncAdapter.sync(context);

        assertEquals(
            0,
            result.savedCount()
        );

        assertEquals(
            "1000",
            result.syncCursor()
        );

        verify(contentRefService)
            .markSyncFailed(
                workspaceId,
                Provider.GMAIL,
                "message-1"
            );

        assertNotNull(
            result.syncedUntil()
        );
    }

    @Test
    void sync_recoversWhenHistoryIdExpired() {
        Long workspaceId = 1L;
        Long userId = 10L;
        String token = "test-token";

        String expiredHistoryId = "1000";
        String recoveredHistoryId = "2000";

        LocalDateTime createdAt =
            LocalDateTime.of(
                2026,
                10,
                17,
                13,
                0
            );

        GmailMessage message =
            new GmailMessage(
                "message-3",
                "thread-3",
                recoveredHistoryId,
                "복구 메일",
                "sender@example.com",
                "receiver@example.com",
                "복구 본문",
                "복구 미리보기",
                createdAt,
                "https://mail.google.com/mail/u/0/#all/message-3"
            );

        when(integrationService.getAccessToken(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(token);

        /*
         * 기존 historyId가 만료된 상황.
         */
        when(gmailApiClient.listHistory(
            token,
            expiredHistoryId,
            5
        )).thenThrow(
            new GmailApiClient.HistoryExpiredException()
        );

        /*
         * 복구 시 현재 Gmail profile에서
         * 새로운 historyId를 가져온다.
         */
        when(gmailApiClient.getProfile(token))
            .thenReturn(
                new GmailProfile(
                    "user@example.com",
                    recoveredHistoryId
                )
            );

        /*
         * 최근 30일 메일을 다시 수집한다.
         */
        when(gmailApiClient.listMessageIds(
            eq(token),
            any(LocalDateTime.class),
            eq(10)
        )).thenReturn(
            List.of("message-3")
        );

        when(gmailApiClient.getMessage(
            token,
            "message-3"
        )).thenReturn(message);

        when(contentRefService.upsert(
            any(ContentRefUpsert.class)
        )).thenReturn(
            new ContentRefDTO(
                102L,
                Provider.GMAIL,
                ContentType.MAIL,
                "message-3",
                "복구 메일",
                "복구 본문",
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
                expiredHistoryId,
                userId
            );

        var result =
            gmailSyncAdapter.sync(context);

        assertEquals(
            1,
            result.savedCount()
        );

        /*
         * 만료된 historyId가 아니라
         * 새 historyId로 cursor가 교체되어야 한다.
         */
        assertEquals(
            recoveredHistoryId,
            result.syncCursor()
        );

        assertNotNull(
            result.syncedUntil()
        );

        /*
         * 기존 historyId로 증분 조회를 먼저 시도했는지 확인.
         */
        verify(gmailApiClient)
            .listHistory(
                token,
                expiredHistoryId,
                5
            );

        /*
         * 만료 복구를 위해 현재 profile을 다시 조회했는지 확인.
         */
        verify(gmailApiClient)
            .getProfile(token);

        verify(gmailApiClient)
            .getMessage(
                token,
                "message-3"
            );

        /*
         * 최근 30일 범위로 재수집했는지 확인.
         * ArgumentCaptor 하나로 호출 여부와 조회 기준 시각을 함께 검증한다.
         */
        ArgumentCaptor<LocalDateTime> afterCaptor =
            ArgumentCaptor.forClass(
                LocalDateTime.class
            );

        verify(gmailApiClient)
            .listMessageIds(
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
    }

    @Test
    void sync_marksDeletedMailsAsOriginDeleted() {
        Long workspaceId = 1L;
        Long userId = 10L;
        String token = "test-token";

        when(integrationService.getAccessToken(
            workspaceId,
            userId,
            Provider.GMAIL
        )).thenReturn(token);

        when(gmailApiClient.listHistory(
            token,
            "1000",
            5
        )).thenReturn(
            new HistoryResult(
                List.of(),
                List.of("mine", "others", "unknown"),
                "1100"
            )
        );

        when(contentRefService.findByOrigin(workspaceId, Provider.GMAIL, "mine"))
            .thenReturn(java.util.Optional.of(mailRef(100L)));
        when(contentRefService.findByOrigin(workspaceId, Provider.GMAIL, "others"))
            .thenReturn(java.util.Optional.of(mailRef(200L)));
        when(contentRefService.findByOrigin(workspaceId, Provider.GMAIL, "unknown"))
            .thenReturn(java.util.Optional.empty());
        when(contentRefService.getOwner(100L))
            .thenReturn(new ContentRefService.ContentOwner(workspaceId, userId, false));
        when(contentRefService.getOwner(200L))
            .thenReturn(new ContentRefService.ContentOwner(workspaceId, 99L, false));

        gmailSyncAdapter.sync(
            new SyncContext(
                workspaceId,
                "",
                userId,
                null,
                "1000",
                userId
            )
        );

        // 내 메일만 원본 삭제로 표시하고, 다른 사람 메일이나 저장 안 된 메일은 건드리지 않는다.
        verify(contentRefService).markOriginDeleted(100L);
        verify(contentRefService, never()).markOriginDeleted(200L);
        verify(gmailApiClient, never()).getMessage(any(), eq("mine"));
    }

    private static ContentRefDTO mailRef(Long contentRefId) {
        return new ContentRefDTO(
            contentRefId,
            Provider.GMAIL,
            ContentType.MAIL,
            "id-" + contentRefId,
            "제목",
            "본문",
            null,
            null,
            LocalDateTime.now(),
            0,
            false
        );
    }
}
