package com.togather.content.notion.service;

import com.togather.analysis.domain.DiscussionAnalysis;
import com.togather.analysis.repository.DiscussionAnalysisRepository;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentDetailDTO;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.client.NotionApiClient;
import com.togather.content.notion.client.NotionApiClient.NotionDatabase;
import com.togather.content.notion.client.NotionApiClient.NotionPage;
import com.togather.content.notion.dto.NotionDatabaseResponse;
import com.togather.content.notion.dto.NotionPageDetailResponse;
import com.togather.content.notion.dto.NotionPageRequest;
import com.togather.content.notion.dto.NotionPageResponse;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.TeamTokenResolver;
import com.togather.integration.service.IntegrationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-03-04 NotionContentService (UC-05-01 ~ 04). 목록은 동기화해 둔 content_ref로 보여주고,
 * 문서를 열 때 Notion에서 최신 본문을 읽는다. 외부 API를 부르는 메서드는 트랜잭션 밖에서 실행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotionContentService {

    private static final Duration DATABASE_CACHE_TTL = Duration.ofHours(1);

    private final ContentRefRepository contentRefRepository;
    private final ContentRefService contentRefService;
    private final DiscussionAnalysisRepository discussionAnalysisRepository;
    private final IntegrationService integrationService;
    private final TeamTokenResolver teamTokenResolver;
    private final NotionApiClient notionApiClient;
    private final NotionSyncAdapter notionSyncAdapter;

    private final Map<String, CachedDatabase> databases = new ConcurrentHashMap<>();

    // ===================== 화면 (UI-05) =====================

    /** 연동한 데이터베이스 목록 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<NotionDatabaseResponse> getDatabases(Long workspaceId, Long userId) {
        List<String> ids = integrationService.getConnectionTargets(workspaceId, Provider.NOTION);
        return ids.stream().map(id -> {
            try {
                String token = token(workspaceId, id, userId);
                return new NotionDatabaseResponse(id, database(token, id).title());
            } catch (BusinessException e) {
                return new NotionDatabaseResponse(id, id);
            }
        }).toList();
    }

    /** 문서 목록: 제목과 최종 수정 시각 (최근 수정순) */
    public List<NotionPageResponse> getPages(Long workspaceId) {
        List<ContentRef> refs = activePages(workspaceId);
        Set<Long> fromAnalysis = analysisPageIds(refs.stream().map(ContentRef::getContentRefId).toList());
        return refs.stream()
                .map(ref -> new NotionPageResponse(ref.getContentRefId(), ref.getOriginId(), ref.getChannelId(),
                        ref.getTitle(), ref.getLastModifiedAt(), ref.getDeepLink(),
                        fromAnalysis.contains(ref.getContentRefId()), ref.isOriginDeleted(), ref.isSyncFailed()))
                .toList();
    }

    /** 문서 본문. Notion에서 최신 본문을 읽어 저장하고, 실패하면 저장된 본문을 보여준다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public NotionPageDetailResponse getPage(Long workspaceId, Long userId, Long contentRefId) {
        ContentRef ref = getPageRef(workspaceId, contentRefId);
        boolean refreshFailed = false;
        boolean bodyEditable = false; // 최신 블록을 확인한 경우에만 본문 수정을 허용한다.
        if (!ref.isOriginDeleted()) {
            try {
                String token = token(workspaceId, ref.getChannelId(), userId);
                NotionPage page = notionApiClient.getPage(token, ref.getOriginId());
                if (page.archived()) {
                    contentRefService.markOriginDeleted(contentRefId);
                } else {
                    NotionApiClient.PageContent content = notionApiClient.getPageContent(token, page.id());
                    notionSyncAdapter.save(workspaceId, token, page, content.text());
                    bodyEditable = content.editable();
                }
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                    contentRefService.markOriginDeleted(contentRefId);
                } else {
                    log.info("Notion 문서 새로고침 실패: workspaceId={}, error={}", workspaceId, e.getErrorCode());
                    refreshFailed = true;
                }
            }
        }
        ContentRef latest = getPageRef(workspaceId, contentRefId);
        return new NotionPageDetailResponse(latest.getContentRefId(), latest.getOriginId(), latest.getTitle(),
                latest.isOriginDeleted() ? latest.getSnapshot() : latest.getContent(), latest.getDeepLink(),
                latest.getLastModifiedAt(), !analysisPageIds(List.of(contentRefId)).isEmpty(),
                latest.isOriginDeleted(), refreshFailed, bodyEditable && !latest.isOriginDeleted());
    }

    /** 새 문서 (UC-05-02) */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ContentRefDTO createContent(Long workspaceId, Long userId, String title, String body, String databaseId) {
        String resolved = resolveDatabase(workspaceId, databaseId);
        String token = token(workspaceId, resolved, userId);
        NotionDatabase database = database(token, resolved);
        NotionPage page = notionApiClient.createPage(token, database, title, body);
        return saveOrFail(workspaceId, token, page, body);
    }

    /** 확정된 분석 결과로 문서를 만든다 (B-08, 명세서 createContent(NotionPageRequest)) [결정 4] */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ContentRefDTO createContent(Long workspaceId, Long userId, NotionPageRequest request) {
        return createContent(workspaceId, userId, request.title(), request.body(), request.targetId());
    }

    /** 제목·본문 수정 (UC-05-03). null인 값은 바꾸지 않는다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ContentRefDTO updateContent(Long workspaceId, Long userId, Long contentRefId, String title, String body) {
        ContentRef ref = getPageRef(workspaceId, contentRefId);
        if (ref.isOriginDeleted()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String token = token(workspaceId, ref.getChannelId(), userId);
        NotionDatabase database = database(token, ref.getChannelId());
        NotionPage page = notionApiClient.updatePage(token, ref.getOriginId(), database.titleProperty(), title, body);
        return saveOrFail(workspaceId, token, page, body != null ? body : ref.getContent());
    }

    /** 휴지통으로 옮기고 원본 삭제로 표시한다. 연결된 Task·분석에서는 보관된 사본을 볼 수 있다. (UC-05-04) */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean deleteContent(Long workspaceId, Long userId, Long contentRefId) {
        ContentRef ref = getPageRef(workspaceId, contentRefId);
        String token = token(workspaceId, ref.getChannelId(), userId);
        notionApiClient.archivePage(token, ref.getOriginId());
        contentRefService.markOriginDeleted(contentRefId);
        return true;
    }

    // ===================== 다른 파트 제공 (docs/shared-interfaces.md) =====================

    public List<ContentRefDTO> getContents(Long workspaceId) {
        return contentRefService.toDtos(activePages(workspaceId));
    }

    public ContentDetailDTO getContentDetail(Long contentRefId) {
        ContentRef ref = contentRefRepository.findById(contentRefId)
                .filter(r -> r.getProvider() == Provider.NOTION)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new ContentDetailDTO(ref.getContentRefId(), Provider.NOTION, ref.getTitle(),
                ref.isOriginDeleted() ? ref.getSnapshot() : ref.getContent(), ref.getDeepLink(), null,
                ref.getCreatedAt(), List.of(), null, ref.isOriginDeleted());
    }

    // ===================== 내부 =====================

    private List<ContentRef> activePages(Long workspaceId) {
        return contentRefRepository
                .findAllByWorkspaceIdAndProviderAndParentRefIdIsNullOrderByLastModifiedAtDesc(workspaceId,
                        Provider.NOTION)
                .stream().filter(ref -> !ref.isOriginDeleted()).toList();
    }

    /** 데이터베이스를 연동 대상으로 고른 연결의 토큰 (TeamTokenResolver). Notion 통합은 공유받은 DB만 볼 수 있다. */
    private String token(Long workspaceId, String databaseId, Long userId) {
        return teamTokenResolver.tokenFor(workspaceId, Provider.NOTION, databaseId, userId);
    }

    private ContentRefDTO saveOrFail(Long workspaceId, String token, NotionPage page, String body) {
        ContentRefDTO saved = notionSyncAdapter.save(workspaceId, token, page, body == null ? "" : body);
        if (saved == null) {
            // Notion에는 반영됐으므로 다음 동기화에서 저장된다.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return saved;
    }

    private ContentRef getPageRef(Long workspaceId, Long contentRefId) {
        return contentRefRepository.findById(contentRefId)
                .filter(r -> r.getProvider() == Provider.NOTION && r.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private String resolveDatabase(Long workspaceId, String databaseId) {
        List<String> linked = integrationService.getConnectionTargets(workspaceId, Provider.NOTION);
        if (linked.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTION_DATABASE_NOT_LINKED);
        }
        if (databaseId == null || databaseId.isBlank()) {
            return linked.getFirst();
        }
        if (!linked.contains(databaseId)) {
            throw new BusinessException(ErrorCode.NOTION_DATABASE_NOT_LINKED);
        }
        return databaseId;
    }

    private Set<Long> analysisPageIds(List<Long> contentRefIds) {
        if (contentRefIds.isEmpty()) {
            return Set.of();
        }
        return discussionAnalysisRepository.findAllByNotionPageRefIdIn(contentRefIds).stream()
                .map(DiscussionAnalysis::getNotionPageRefId)
                .collect(Collectors.toSet());
    }

    private NotionDatabase database(String token, String databaseId) {
        CachedDatabase cached = databases.get(databaseId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.database();
        }
        NotionDatabase database = notionApiClient.getDatabase(token, databaseId);
        databases.put(databaseId, new CachedDatabase(database, Instant.now().plus(DATABASE_CACHE_TTL)));
        return database;
    }

    private record CachedDatabase(NotionDatabase database, Instant expiresAt) {
    }
}
