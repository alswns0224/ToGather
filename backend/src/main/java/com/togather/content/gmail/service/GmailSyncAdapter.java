package com.togather.content.gmail.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.gmail.client.GmailApiClient;
import com.togather.content.gmail.client.GmailApiClient.GmailMessage;
import com.togather.content.gmail.client.GmailApiClient.GmailProfile;
import com.togather.content.gmail.client.GmailApiClient.HistoryExpiredException;
import com.togather.content.gmail.client.GmailApiClient.HistoryResult;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gmail 개인 메일 증분 동기화 (C-05).
 *
 * 최초 동기화:
 * - 현재 Gmail historyId를 확보한다.
 * - 최근 30일 메일을 수집한다.
 *
 * 이후 동기화:
 * - 이전 historyId를 기준으로 Gmail history.list를 호출한다.
 * - 변경된 메일만 다시 조회하여 저장한다.
 *
 * historyId가 만료된 경우:
 * - 최근 30일을 다시 수집하고 최신 historyId로 cursor를 교체한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GmailSyncAdapter implements SyncAdapter {

    private static final int FIRST_SYNC_DAYS = 30;
    private static final int FIRST_SYNC_MAX_PAGES = 10;
    private static final int HISTORY_MAX_PAGES = 5;

    private final GmailApiClient gmailApiClient;
    private final ContentRefService contentRefService;
    private final IntegrationService integrationService;

    @Override
    public Provider provider() {
        return Provider.GMAIL;
    }

    /**
     * Gmail은 개인 서비스이므로 현재 요청한 사용자의 Gmail 연결만 대상으로 한다.
     *
     * 스케줄러(requestedBy == null)는 SyncService가 기존 SyncState의
     * ownerUserId를 다시 동기화 대상으로 추가한다.
     */
    @Override
    public List<SyncTarget> findTargets(
        Long workspaceId,
        Long requestedBy
    ) {
        if (requestedBy == null) {
            return List.of();
        }

        if (!integrationService.hasConnection(
            workspaceId,
            requestedBy,
            Provider.GMAIL
        )) {
            return List.of();
        }

        return List.of(
            SyncTarget.personal(requestedBy)
        );
    }

    @Override
    public SyncResult sync(SyncContext context) {
        Long ownerUserId =
            context.ownerUserId();

        if (ownerUserId == null) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        LocalDateTime startedAt =
            LocalDateTime.now(
                GmailApiClient.ZONE
            );

        String token =
            integrationService.getAccessToken(
                context.workspaceId(),
                ownerUserId,
                Provider.GMAIL
            );

        String previousHistoryId =
            normalizeCursor(
                context.syncCursor()
            );

        /*
         * cursor가 없으면 최초 동기화.
         */
        if (previousHistoryId == null) {
            return initialSync(
                context,
                token,
                startedAt
            );
        }

        /*
         * cursor가 있으면 Gmail history.list 기반 증분 동기화.
         */
        try {
            return incrementalSync(
                context,
                token,
                previousHistoryId,
                startedAt
            );

        } catch (HistoryExpiredException e) {
            /*
             * Gmail은 오래된 historyId에 대해 404를 반환할 수 있다.
             * 이 경우 최근 기간을 다시 가져오고 새로운 historyId로 복구한다.
             */
            log.info(
                "Gmail historyId 만료 - 최근 메일 재동기화: workspaceId={}, ownerUserId={}",
                context.workspaceId(),
                ownerUserId
            );

            return recoverFromExpiredHistory(
                context,
                token,
                startedAt
            );
        }
    }

    /**
     * 최초 Gmail 동기화.
     *
     * 먼저 현재 historyId를 확보한 뒤 최근 메일을 가져온다.
     * 동기화 도중 새 변경이 발생하더라도 다음 history.list에서 확인할 수 있다.
     */
    private SyncResult initialSync(
        SyncContext context,
        String token,
        LocalDateTime startedAt
    ) {
        GmailProfile profile =
            gmailApiClient.getProfile(
                token
            );

        List<String> messageIds =
            gmailApiClient.listMessageIds(
                token,
                startedAt.minusDays(
                    FIRST_SYNC_DAYS
                ),
                FIRST_SYNC_MAX_PAGES
            );

        int saved =
            saveMessages(
                context.workspaceId(),
                context.ownerUserId(),
                token,
                messageIds
            );

        return new SyncResult(
            saved,
            startedAt,
            normalizeCursor(
                profile.historyId()
            )
        );
    }

    /**
     * Gmail historyId 기반 증분 동기화.
     */
    private SyncResult incrementalSync(
        SyncContext context,
        String token,
        String previousHistoryId,
        LocalDateTime startedAt
    ) {
        HistoryResult history =
            gmailApiClient.listHistory(
                token,
                previousHistoryId,
                HISTORY_MAX_PAGES
            );

        /*
         * history 응답에서 동일한 messageId가 여러 번 나타날 수 있으므로
         * 중복 제거 후 한 번씩만 조회한다.
         */
        Set<String> changedMessageIds =
            new LinkedHashSet<>(
                history.changedMessageIds()
            );

        int saved =
            saveMessages(
                context.workspaceId(),
                context.ownerUserId(),
                token,
                changedMessageIds
            );

        /*
         * 삭제된 Gmail 메시지는 현재 ContentRefService에
         * 공통 삭제 API가 없으므로 여기서는 별도로 삭제하지 않는다.
         */
        if (!history.deletedMessageIds()
            .isEmpty()) {

            log.debug(
                "Gmail 삭제 이력 감지: workspaceId={}, ownerUserId={}, count={}",
                context.workspaceId(),
                context.ownerUserId(),
                history.deletedMessageIds()
                    .size()
            );
        }

        String latestHistoryId =
            normalizeCursor(
                history.latestHistoryId()
            );

        /*
         * Gmail 응답에 새 cursor가 없다면 기존 cursor를 유지한다.
         */
        if (latestHistoryId == null) {
            latestHistoryId =
                previousHistoryId;
        }

        return new SyncResult(
            saved,
            startedAt,
            latestHistoryId
        );
    }

    /**
     * historyId 만료 시 최근 기간을 다시 수집하여 상태를 복구한다.
     */
    private SyncResult recoverFromExpiredHistory(
        SyncContext context,
        String token,
        LocalDateTime startedAt
    ) {
        GmailProfile profile =
            gmailApiClient.getProfile(
                token
            );

        List<String> messageIds =
            gmailApiClient.listMessageIds(
                token,
                startedAt.minusDays(
                    FIRST_SYNC_DAYS
                ),
                FIRST_SYNC_MAX_PAGES
            );

        int saved =
            saveMessages(
                context.workspaceId(),
                context.ownerUserId(),
                token,
                messageIds
            );

        return new SyncResult(
            saved,
            startedAt,
            normalizeCursor(
                profile.historyId()
            )
        );
    }

    private int saveMessages(
        Long workspaceId,
        Long ownerUserId,
        String token,
        Iterable<String> messageIds
    ) {
        int saved = 0;

        for (String messageId : messageIds) {
            GmailMessage message =
                gmailApiClient.getMessage(
                    token,
                    messageId
                );

            if (save(
                workspaceId,
                ownerUserId,
                message
            ) != null) {
                saved++;
            }
        }

        return saved;
    }

    /**
     * Gmail 메일을 공통 ContentRef로 저장한다.
     */
    ContentRefDTO save(
        Long workspaceId,
        Long ownerUserId,
        GmailMessage message
    ) {
        try {
            String content =
                message.content();

            if (content == null
                || content.isBlank()) {
                content =
                    message.snippet();
            }

            return contentRefService.upsert(
                new ContentRefUpsert(
                    workspaceId,
                    ownerUserId,
                    Provider.GMAIL,
                    ContentType.MAIL,
                    message.id(),
                    null,
                    null,
                    message.subject(),
                    content,
                    message.deepLink(),
                    message.createdAt(),
                    message.createdAt(),
                    null,
                    message.from()
                )
            );

        } catch (RuntimeException e) {
            log.warn(
                "Gmail 메일 저장 실패: workspaceId={}, ownerUserId={}, messageId={}, error={}",
                workspaceId,
                ownerUserId,
                message.id(),
                e instanceof BusinessException be
                    ? be.getErrorCode()
                    : e.getClass()
                    .getSimpleName()
            );

            try {
                contentRefService.markSyncFailed(
                    workspaceId,
                    Provider.GMAIL,
                    message.id()
                );
            } catch (RuntimeException ignored) {
                // 기존 ContentRef가 없으면 다음 동기화에서 다시 시도한다.
            }

            return null;
        }
    }

    private String normalizeCursor(
        String cursor
    ) {
        if (cursor == null
            || cursor.isBlank()) {
            return null;
        }

        return cursor;
    }
}
