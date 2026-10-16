package com.togather.content.gmail.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.gmail.client.GmailApiClient;
import com.togather.content.gmail.client.GmailApiClient.GmailMessage;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gmail 개인 메일 증분 동기화 (C-05).
 *
 * Gmail은 개인 서비스이므로 사용자별로 동기화한다.
 * 현재 SyncAdapter가 시간(since) 기반이므로 최근 변경 범위를 다시 조회한다.
 *
 * TODO: Sync 프레임워크에서 문자열 cursor(historyId)를 지원하면
 * Gmail history.list 기반 증분 동기화로 전환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GmailSyncAdapter implements SyncAdapter {

    private static final int FIRST_SYNC_DAYS = 30;
    private static final int LOOKBACK_MINUTES = 5;

    private static final int FIRST_SYNC_MAX_PAGES = 10;
    private static final int INCREMENTAL_MAX_PAGES = 5;

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
     * 스케줄러(requestedBy == null)는 SyncService가 기존 SyncState의 ownerUserId를
     * 다시 대상에 추가한다.
     */
    @Override
    public List<SyncTarget> findTargets(Long workspaceId, Long requestedBy) {
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
        Long ownerUserId = context.ownerUserId();

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

        LocalDateTime after;

        int maxPages;

        if (context.since() == null) {
            after = startedAt.minusDays(
                FIRST_SYNC_DAYS
            );

            maxPages =
                FIRST_SYNC_MAX_PAGES;
        } else {
            /*
             * Gmail 메일의 internalDate와 SyncService 성공 시각 사이의
             * 경계에서 메일을 놓치지 않도록 조금 겹쳐서 다시 조회한다.
             *
             * ContentRefService.upsert가 originId 기준으로 중복을 처리하므로
             * 같은 메일이 다시 조회돼도 중복 저장되지 않는다.
             */
            after = context.since()
                .minusMinutes(
                    LOOKBACK_MINUTES
                );

            maxPages =
                INCREMENTAL_MAX_PAGES;
        }

        List<String> messageIds =
            gmailApiClient.listMessageIds(
                token,
                after,
                maxPages
            );

        int saved = 0;

        for (String messageId : messageIds) {
            GmailMessage message =
                gmailApiClient.getMessage(
                    token,
                    messageId
                );

            if (save(
                context.workspaceId(),
                ownerUserId,
                message
            ) != null) {
                saved++;
            }
        }

        return new SyncResult(
            saved,
            startedAt
        );
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
                    : e.getClass().getSimpleName()
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
}
