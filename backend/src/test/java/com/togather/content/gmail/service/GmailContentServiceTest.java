package com.togather.content.gmail.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.sync.service.SyncService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GmailContentServiceTest {

    @Mock
    private ContentRefService contentRefService;

    @Mock
    private SyncService syncService;

    private GmailContentService gmailContentService;

    @BeforeEach
    void setUp() {
        gmailContentService = new GmailContentService(
            contentRefService,
            syncService
        );
    }

    @Test
    void getContentsByUser_syncsIfStaleAndReturnsWorkspaceScopedContents() {
        Long workspaceId = 1L;
        Long userId = 10L;

        List<ContentRefDTO> expected = List.of();

        when(contentRefService.getPersonalContents(
            workspaceId,
            userId,
            Provider.GMAIL,
            null
        )).thenReturn(expected);

        List<ContentRefDTO> result =
            gmailContentService.getContentsByUser(
                workspaceId,
                userId
            );

        assertSame(expected, result);

        verify(syncService).syncIfStale(
            workspaceId,
            Provider.GMAIL,
            userId,
            Duration.ofMinutes(5)
        );

        verify(contentRefService).getPersonalContents(
            workspaceId,
            userId,
            Provider.GMAIL,
            null
        );
    }

    @Test
    void getContentsByUserWithSince_returnsOnlyRequestedRange() {
        Long workspaceId = 1L;
        Long userId = 10L;
        LocalDateTime since =
            LocalDateTime.of(
                2026,
                10,
                1,
                0,
                0
            );

        List<ContentRefDTO> expected = List.of();

        when(contentRefService.getPersonalContents(
            workspaceId,
            userId,
            Provider.GMAIL,
            since
        )).thenReturn(expected);

        List<ContentRefDTO> result =
            gmailContentService.getContentsByUser(
                workspaceId,
                userId,
                since
            );

        assertSame(expected, result);

        verify(contentRefService).getPersonalContents(
            workspaceId,
            userId,
            Provider.GMAIL,
            since
        );
    }
}
