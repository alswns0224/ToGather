package com.togather.search.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.slack.service.SlackMessageFormat;
import com.togather.search.client.EmbeddingClient;
import com.togather.search.client.VectorStore;
import com.togather.search.client.VectorStore.VectorHit;
import com.togather.search.config.SearchProperties;
import com.togather.search.dto.SearchResponse;
import com.togather.search.dto.SearchResponse.Item;
import com.togather.task.dto.TaskSummary;
import com.togather.task.service.TaskContextService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 문맥 기반 통합 검색 (C-08, UC-12-01). 질의를 BGE-M3로 임베딩하고 Chroma에서 가까운 콘텐츠를 찾아
 * 관련도순으로 돌려준다. 원본이 지워진 콘텐츠는 빼고, 연결된 Task를 함께 보여준다.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    /** 통합 검색 대상 (팀이 함께 보는 서비스). Gmail·개인 일정은 넣지 않는다. */
    public static final Set<Provider> SEARCHABLE = Set.of(Provider.SLACK, Provider.NOTION, Provider.GOOGLE_DRIVE);
    static final int MAX_QUERY = 200;
    static final int MAX_LIMIT = 50;
    static final int SNIPPET = 160;

    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ContentRefRepository contentRefRepository;
    private final TaskContextService taskContextService;
    private final ContentIndexService contentIndexService;
    private final SearchProperties properties;

    public SearchResponse search(Long workspaceId, Long userId, String query, Collection<Provider> providers,
                                 int limit) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        String q = query == null ? "" : query.strip();
        if (q.isEmpty() || q.length() > MAX_QUERY) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Set<Provider> targets = providers == null || providers.isEmpty() ? SEARCHABLE : Set.copyOf(providers);
        if (!SEARCHABLE.containsAll(targets)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
        int size = Math.clamp(limit, 1, MAX_LIMIT);

        // 원본이 지워졌지만 아직 색인에서 빠지지 않은 것을 고려해 넉넉히 받아 거른다.
        List<VectorHit> hits = vectorStore.searchVectors(embeddingClient.embed(q), workspaceId,
                targets.stream().map(Enum::name).toList(), size * 2);
        Map<Long, ContentRef> refs = contentRefRepository.findAllById(hits.stream().map(VectorHit::contentRefId).toList())
                .stream().collect(Collectors.toMap(ContentRef::getContentRefId, Function.identity()));

        List<VectorHit> kept = new ArrayList<>();
        for (VectorHit hit : hits) {
            ContentRef ref = refs.get(hit.contentRefId());
            if (ref != null && ref.getWorkspaceId().equals(workspaceId) && targets.contains(ref.getProvider())
                    && !ref.isOriginDeleted() && !ref.isDriveTrashed() && score(hit) >= properties.minScore()) {
                kept.add(hit);
            }
            if (kept.size() == size) {
                break;
            }
        }

        List<Long> ids = kept.stream().map(VectorHit::contentRefId).toList();
        Map<Long, List<TaskSummary>> tasks = taskContextService.getTasksByContents(ids);
        Map<Long, ContentRef> roots = slackRoots(kept.stream().map(h -> refs.get(h.contentRefId())).toList());
        List<Item> items = kept.stream()
                .map(hit -> toItem(refs.get(hit.contentRefId()), score(hit), roots,
                        tasks.getOrDefault(hit.contentRefId(), List.of())))
                .toList();
        return new SearchResponse(q, items, contentIndexService.countPending(workspaceId));
    }

    // ===================== 내부 =====================

    private static double score(VectorHit hit) {
        return Math.round((1 - hit.distance()) * 100) / 100.0;
    }

    /** Slack 답글은 스레드로 열어야 하므로 root를 함께 읽는다. */
    private Map<Long, ContentRef> slackRoots(List<ContentRef> refs) {
        List<Long> parentIds = refs.stream()
                .filter(r -> r.getProvider() == Provider.SLACK && r.getParentRefId() != null)
                .map(ContentRef::getParentRefId)
                .distinct()
                .toList();
        return contentRefRepository.findAllById(parentIds).stream()
                .collect(Collectors.toMap(ContentRef::getContentRefId, Function.identity()));
    }

    private static Item toItem(ContentRef ref, double score, Map<Long, ContentRef> roots, List<TaskSummary> tasks) {
        String body = ref.getProvider() == Provider.SLACK
                ? SlackMessageFormat.parse(ref.getContent()).text()
                : ref.getContent();
        String snippet = body == null ? "" : body.strip().replaceAll("\\s+", " ");
        if (snippet.length() > SNIPPET) {
            snippet = snippet.substring(0, SNIPPET) + "…";
        }
        return new Item(ref.getContentRefId(), ref.getProvider(), ref.getTitle(), snippet, ref.getDeepLink(),
                ref.getCreatedAt(), ref.getAuthorName(), score, appPath(ref, roots), tasks);
    }

    static String appPath(ContentRef ref, Map<Long, ContentRef> roots) {
        return switch (ref.getProvider()) {
            case SLACK -> {
                ContentRef root = ref.getParentRefId() == null ? ref
                        : Objects.requireNonNullElse(roots.get(ref.getParentRefId()), ref);
                yield "/slack?channel=" + ref.getChannelId() + "&thread=" + SlackMessageFormat.tsOf(root.getOriginId());
            }
            case NOTION -> "/notion?page=" + ref.getContentRefId();
            case GOOGLE_DRIVE -> "/drive";
            default -> "";
        };
    }
}
