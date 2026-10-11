package com.togather.content.notion.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.client.NotionApiClient;
import com.togather.content.notion.client.NotionApiClient.NotionPage;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Notion 데이터베이스 증분 동기화 (B-06).
 * 매번 페이지 목록(제목·수정 시각)은 전부 받고, 마지막 동기화 이후 수정된 페이지만 본문을 다시 읽는다.
 * 목록에서 사라진 페이지는 원본 삭제로 표시한다. (Notion API는 삭제된 페이지를 따로 알려주지 않는다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotionSyncAdapter implements SyncAdapter {

    private static final int MAX_QUERY_PAGES = 5;

    private final NotionApiClient notionApiClient;
    private final ContentRefService contentRefService;
    private final ContentRefRepository contentRefRepository;
    private final IntegrationService integrationService;

    @Override
    public Provider provider() {
        return Provider.NOTION;
    }

    @Override
    public List<SyncTarget> findTargets(Long workspaceId, Long requestedBy) {
        return integrationService.getConnectionTargets(workspaceId, Provider.NOTION).stream()
                .map(SyncTarget::team)
                .toList();
    }

    @Override
    public SyncResult sync(SyncContext context) {
        LocalDateTime startedAt = LocalDateTime.now(NotionApiClient.ZONE);
        String token = token(context.workspaceId(), context.requestedBy());
        String databaseId = context.targetId();
        List<NotionPage> pages = notionApiClient.queryDatabase(token, databaseId, MAX_QUERY_PAGES);

        int saved = 0;
        for (NotionPage page : pages) {
            if (page.archived()) {
                continue;
            }
            boolean changed = context.since() == null || page.lastEditedAt() == null
                    || !page.lastEditedAt().isBefore(context.since().minusMinutes(1))
                    || contentRefService.findByOrigin(context.workspaceId(), Provider.NOTION, page.id()).isEmpty();
            if (changed && save(context.workspaceId(), token, page, null) != null) {
                saved++;
            }
        }
        if (pages.size() < MAX_QUERY_PAGES * 100) {
            markRemoved(context.workspaceId(), databaseId, pages);
        }
        return new SyncResult(saved, startedAt);
    }

    /**
     * 페이지를 저장한다. text가 null이면 Notion에서 본문을 읽는다. 실패하면 실패 표시만 하고 null.
     */
    ContentRefDTO save(Long workspaceId, String token, NotionPage page, String text) {
        try {
            String body = text != null ? text : notionApiClient.getPageText(token, page.id());
            return contentRefService.upsert(new ContentRefUpsert(workspaceId, null, Provider.NOTION, ContentType.PAGE,
                    page.id(), page.databaseId(), null, page.title(), body, page.url(), page.createdAt(),
                    page.lastEditedAt(), null, null));
        } catch (RuntimeException e) {
            log.warn("Notion 페이지 저장 실패: workspaceId={}, error={}", workspaceId,
                    e instanceof BusinessException be ? be.getErrorCode() : e.getClass().getSimpleName());
            contentRefService.markSyncFailed(workspaceId, Provider.NOTION, page.id());
            return null;
        }
    }

    String token(Long workspaceId, Long requestedBy) {
        if (requestedBy != null) {
            return integrationService.getAccessToken(workspaceId, requestedBy, Provider.NOTION);
        }
        return integrationService.findTargetAccess(workspaceId, Provider.NOTION)
                .map(IntegrationService.TargetAccess::accessToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
    }

    private void markRemoved(Long workspaceId, String databaseId, List<NotionPage> pages) {
        Set<String> alive = pages.stream().filter(p -> !p.archived()).map(NotionPage::id).collect(Collectors.toSet());
        for (ContentRef ref : contentRefRepository.findAllByWorkspaceIdAndProviderAndChannelIdAndIsOriginDeletedFalse(
                workspaceId, Provider.NOTION, databaseId)) {
            if (!alive.contains(ref.getOriginId())) {
                contentRefService.markOriginDeleted(ref.getContentRefId());
            }
        }
    }
}
