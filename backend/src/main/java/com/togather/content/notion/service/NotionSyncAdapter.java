package com.togather.content.notion.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.client.NotionApiClient;
import com.togather.content.notion.client.NotionApiClient.NotionPage;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.integration.client.NotionOAuthClient;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.togather.content.service.TeamTokenResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Notion 증분 동기화 (B-06).
 * 연동 대상은 데이터베이스이거나 페이지("page:" + ID)다. 페이지면 그 아래 하위 페이지와 페이지 안 데이터베이스의 행까지 모은다.
 * 매번 페이지 목록(제목·수정 시각)은 전부 받고, 마지막 동기화 이후 수정된 페이지만 본문을 다시 읽는다.
 * 목록에서 사라진 페이지는 원본 삭제로 표시한다. (Notion API는 삭제된 페이지를 따로 알려주지 않는다)
 * 저장할 때 channelId는 연동 대상 ID로 둔다. (사라진 페이지 판단과 토큰 찾기에 쓴다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotionSyncAdapter implements SyncAdapter {

    private static final int MAX_QUERY_PAGES = 5;
    /** 페이지 대상: 하위 페이지를 따라 내려가는 최대 깊이와 최대 페이지 수 */
    private static final int MAX_CRAWL_DEPTH = 3;
    private static final int MAX_CRAWL_PAGES = 200;

    private final NotionApiClient notionApiClient;
    private final ContentRefService contentRefService;
    private final ContentRefRepository contentRefRepository;
    private final IntegrationService integrationService;
    private final TeamTokenResolver teamTokenResolver;

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
        String targetId = context.targetId();
        String token = teamTokenResolver.tokenFor(context.workspaceId(), Provider.NOTION, targetId,
                context.requestedBy());
        List<NotionPage> pages;
        boolean complete;
        if (NotionOAuthClient.isPageTarget(targetId)) {
            NotionApiClient.PageTree tree = notionApiClient.crawlPage(token, NotionOAuthClient.pageId(targetId),
                    MAX_CRAWL_DEPTH, MAX_CRAWL_PAGES);
            pages = tree.pages();
            complete = !tree.truncated();
        } else {
            pages = notionApiClient.queryDatabase(token, targetId, MAX_QUERY_PAGES);
            complete = pages.size() < MAX_QUERY_PAGES * 100;
        }

        // 이미 저장한 페이지를 한 번에 가져온다. (페이지마다 조회하지 않도록, README N+1 규칙)
        Set<String> stored = contentRefRepository
                .findAllByWorkspaceIdAndProviderAndChannelIdAndIsOriginDeletedFalse(context.workspaceId(),
                        Provider.NOTION, targetId)
                .stream().map(ContentRef::getOriginId).collect(Collectors.toSet());

        int saved = 0;
        for (NotionPage page : pages) {
            if (page.archived()) {
                continue;
            }
            boolean changed = context.since() == null || page.lastEditedAt() == null
                    || !page.lastEditedAt().isBefore(context.since().minusMinutes(1))
                    || !stored.contains(page.id());
            if (changed && save(context.workspaceId(), token, page, null, targetId) != null) {
                saved++;
            }
        }
        if (complete) {
            markRemoved(context.workspaceId(), targetId, pages);
        }
        return new SyncResult(saved, startedAt);
    }

    /**
     * 페이지를 저장한다. text가 null이면 Notion에서 본문을 읽는다. 실패하면 실패 표시만 하고 null.
     *
     * @param targetId 이 페이지를 가져온 연동 대상 (처음 저장할 때 channelId가 된다)
     */
    ContentRefDTO save(Long workspaceId, String token, NotionPage page, String text, String targetId) {
        try {
            String body = text != null ? text : notionApiClient.getPageText(token, page.id());
            return contentRefService.upsert(new ContentRefUpsert(workspaceId, null, Provider.NOTION, ContentType.PAGE,
                    page.id(), targetId, null, page.title(), body, page.url(), page.createdAt(),
                    page.lastEditedAt(), null, null));
        } catch (RuntimeException e) {
            log.warn("Notion 페이지 저장 실패: workspaceId={}, error={}", workspaceId,
                    e instanceof BusinessException be ? be.getErrorCode() : e.getClass().getSimpleName());
            contentRefService.markSyncFailed(workspaceId, Provider.NOTION, page.id());
            return null;
        }
    }

    private void markRemoved(Long workspaceId, String targetId, List<NotionPage> pages) {
        Set<String> alive = pages.stream().filter(p -> !p.archived()).map(NotionPage::id).collect(Collectors.toSet());
        for (ContentRef ref : contentRefRepository.findAllByWorkspaceIdAndProviderAndChannelIdAndIsOriginDeletedFalse(
                workspaceId, Provider.NOTION, targetId)) {
            if (!alive.contains(ref.getOriginId())) {
                contentRefService.markOriginDeleted(ref.getContentRefId());
            }
        }
    }
}
