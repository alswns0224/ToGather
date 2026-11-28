package com.togather.search.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.access.ContentAccessService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
    /** 문서 하나가 여러 조각으로 나와도 문서 수가 모자라지 않게, 보여 줄 수의 이 배수만큼 조각을 받는다 */
    static final int CHUNK_FETCH_FACTOR = 6;
    static final int MAX_CHUNK_FETCH = 300;
    /**
     * 낱말이 그대로 들어 있는 결과의 가장 낮은 관련도. 제목에 있거나 여러 번 나오면 더 높게 준다. (keywordScore)
     * 문맥 검색 점수가 더 높으면 그 점수를 쓴다.
     */
    static final double KEYWORD_SCORE = 0.55;
    /** 검색어가 제목(파일·문서 이름)에 들어 있으면 더하는 점수. Slack 메시지의 제목은 채널 이름이라 더하지 않는다 */
    static final double KEYWORD_TITLE_BONUS = 0.2;
    /** 검색어가 한 번 더 나올 때마다 더하는 점수와 그 한도 */
    static final double KEYWORD_REPEAT_BONUS = 0.05;
    static final double KEYWORD_REPEAT_MAX = 0.15;
    /** 이 글자 수 이하인 Slack 메시지는 문맥 검색에서 뺀다. ("ㅎㅇ", "ㅇㅋ" 등) */
    static final int MIN_MEANINGFUL_CHARS = 3;
    /** DriveSyncAdapter가 파일 본문 앞에 붙이는 표시 */
    private static final String DRIVE_BODY_MARKER = "\n\n본문:\n";

    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ContentRefRepository contentRefRepository;
    private final TaskContextService taskContextService;
    private final ContentIndexService contentIndexService;
    private final SearchProperties properties;
    private final ContentAccessService contentAccess;

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

        // 긴 문서는 조각 여러 개가 나오므로 넉넉히 받아, 문서마다 가장 가까운 조각 하나만 남긴다.
        // (원본이 지워졌지만 아직 색인에서 빠지지 않은 것도 아래에서 거른다)
        List<VectorHit> hits = bestChunkPerContent(vectorStore.searchVectors(embeddingClient.embed(q), workspaceId,
                targets.stream().map(Enum::name).toList(), Math.min(MAX_CHUNK_FETCH, size * CHUNK_FETCH_FACTOR)));
        Map<Long, ContentRef> refs = contentRefRepository.findAllById(hits.stream().map(VectorHit::contentRefId).toList())
                .stream().collect(Collectors.toMap(ContentRef::getContentRefId, Function.identity()));

        List<VectorHit> kept = new ArrayList<>();
        for (VectorHit hit : hits) {
            ContentRef ref = refs.get(hit.contentRefId());
            if (ref != null && ref.getOwnerUserId() == null && ref.getWorkspaceId().equals(workspaceId) && targets.contains(ref.getProvider())
                    && contentAccess.canView(workspaceId, userId, ref)
                    && !ref.isOriginDeleted() && !ref.isDriveTrashed() && score(hit) >= properties.minScore()
                    && !isTooShort(ref)) {
                kept.add(hit);
            }
            if (kept.size() == size) {
                break;
            }
        }
        Map<Long, Integer> bestChunks = new LinkedHashMap<>();
        kept.forEach(hit -> bestChunks.put(hit.contentRefId(), hit.chunk()));

        // 낱말 찾기: 문맥 점수가 낮아도 검색어가 글자 그대로 들어 있는 문서·파일은 함께 보여 준다.
        Map<Long, Double> scores = new LinkedHashMap<>();
        kept.forEach(hit -> scores.put(hit.contentRefId(), score(hit)));
        for (ContentRef ref : contentAccess.filter(workspaceId, userId, keywordMatches(workspaceId, targets, q, size),
                ref -> ref)) {
            refs.putIfAbsent(ref.getContentRefId(), ref);
            scores.merge(ref.getContentRefId(), keywordScore(ref, q), Math::max);
        }
        List<Long> ids = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(size)
                .map(Map.Entry::getKey)
                .toList();

        Map<Long, List<TaskSummary>> tasks = taskContextService.getTasksByContents(ids);
        Map<Long, ContentRef> roots = slackRoots(ids.stream().map(refs::get).toList());
        List<Item> items = ids.stream()
                .map(id -> toItem(refs.get(id), scores.get(id), roots, tasks.getOrDefault(id, List.of()), q,
                        bestChunks.get(id)))
                .toList();
        return new SearchResponse(q, items, contentIndexService.countPending(workspaceId));
    }

    // ===================== 내부 =====================

    /** 거리순으로 온 조각 목록에서 콘텐츠마다 처음(가장 가까운) 조각만 남긴다. */
    static List<VectorHit> bestChunkPerContent(List<VectorHit> hits) {
        Map<Long, VectorHit> best = new LinkedHashMap<>();
        hits.stream().sorted(java.util.Comparator.comparingDouble(VectorHit::distance))
                .forEach(hit -> best.putIfAbsent(hit.contentRefId(), hit));
        return List.copyOf(best.values());
    }

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

    /**
     * "ㅎㅇ"처럼 아주 짧은 Slack 메시지는 뜻이 거의 없는데도 문맥 점수가 높게 나와(0.5 이상) 문맥 검색 결과에서 뺀다.
     * 검색어가 그대로 들어 있으면 낱말 찾기로는 나온다.
     */
    static boolean isTooShort(ContentRef ref) {
        if (ref.getProvider() != Provider.SLACK) {
            return false;
        }
        String text = SlackMessageFormat.parse(ref.getContent()).text();
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return compact.codePointCount(0, compact.length()) <= MIN_MEANINGFUL_CHARS;
    }

    /** 제목·본문에 검색어가 그대로 들어 있는 콘텐츠 (두 글자 이상일 때만) */
    private List<ContentRef> keywordMatches(Long workspaceId, Set<Provider> targets, String q, int size) {
        if (q.length() < 2) {
            return List.of();
        }
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return contentRefRepository.searchByKeyword(workspaceId, targets, "%" + escaped + "%", PageRequest.of(0, size));
    }

    /**
     * @param chunk 문맥 검색으로 찾았으면 가장 잘 맞은 조각 번호. 검색어가 글자 그대로 없으면 그 조각을 미리보기로 보여 준다.
     */
    private static Item toItem(ContentRef ref, double score, Map<Long, ContentRef> roots, List<TaskSummary> tasks,
                               String query, Integer chunk) {
        String body = ref.getProvider() == Provider.SLACK
                ? SlackMessageFormat.parse(ref.getContent()).text()
                : bodyForSnippet(ref);
        String flat = body == null ? "" : body.strip().replaceAll("\\s+", " ");
        String snippet = chunk != null && !containsIgnoreCase(flat, query)
                ? chunkSnippet(ref, chunk, flat, query)
                : snippet(flat, query);
        return new Item(ref.getContentRefId(), ref.getProvider(), ref.getTitle(), snippet, ref.getDeepLink(),
                ref.getCreatedAt(), ref.getAuthorName(), score, appPath(ref, roots), tasks);
    }

    /** 낱말 찾기 결과의 관련도: 기본 0.55, 제목에 있으면 +0.2, 한 번 더 나올 때마다 +0.05 (최대 +0.15) */
    static double keywordScore(ContentRef ref, String query) {
        String q = query.toLowerCase();
        boolean slack = ref.getProvider() == Provider.SLACK;
        String title = slack || ref.getTitle() == null ? "" : ref.getTitle().toLowerCase();
        String body = slack ? SlackMessageFormat.parse(ref.getContent()).text() : ref.getContent();
        String content = body == null ? "" : body.toLowerCase();
        int count = occurrences(title, q) + occurrences(content, q);
        double score = KEYWORD_SCORE
                + (title.contains(q) ? KEYWORD_TITLE_BONUS : 0)
                + Math.min(KEYWORD_REPEAT_MAX, KEYWORD_REPEAT_BONUS * Math.max(0, count - 1));
        return Math.round(score * 100) / 100.0;
    }

    private static int occurrences(String text, String q) {
        int count = 0;
        for (int at = text.indexOf(q); at >= 0; at = text.indexOf(q, at + q.length())) {
            count++;
        }
        return count;
    }

    private static boolean containsIgnoreCase(String text, String query) {
        return query != null && !query.isBlank() && text.toLowerCase().contains(query.toLowerCase());
    }

    /** 가장 잘 맞은 조각의 앞부분. 조각을 다시 만들 수 없으면 원래 미리보기 */
    private static String chunkSnippet(ContentRef ref, int chunk, String flat, String query) {
        List<String> chunks = ContentIndexService.chunksOf(ref);
        if (chunk <= 0 || chunk >= chunks.size()) {
            return snippet(flat, query);
        }
        String text = chunks.get(chunk).replaceAll("\\s+", " ");
        // 검색어 낱말(두 글자 이상) 중 조각 안에 처음 나오는 곳을 가운데로 보여 준다. 없으면 조각 앞부분.
        int at = -1;
        String lower = text.toLowerCase();
        for (String word : query == null ? new String[0] : query.toLowerCase().split("\\s+")) {
            int found = word.length() < 2 ? -1 : lower.indexOf(word);
            if (found >= 0 && (at < 0 || found < at)) {
                at = found;
            }
        }
        int start = at < 0 ? 0 : Math.max(0, Math.min(at - SNIPPET / 3, text.length() - SNIPPET));
        int end = Math.min(text.length(), start + SNIPPET);
        return "…" + text.substring(start, end) + (end < text.length() ? "…" : "");
    }

    /** Drive 파일은 "파일 유형" 줄 대신 추출한 본문을 미리보기로 보여준다. */
    private static String bodyForSnippet(ContentRef ref) {
        String content = ref.getContent();
        if (ref.getProvider() == Provider.GOOGLE_DRIVE && content != null && content.contains(DRIVE_BODY_MARKER)) {
            int at = content.indexOf(DRIVE_BODY_MARKER);
            String body = content.substring(at + DRIVE_BODY_MARKER.length());
            return body.isBlank() ? content.substring(0, at) : body;
        }
        return content;
    }

    /** 검색어가 들어 있으면 그 주변을, 없으면 앞부분을 미리보기로 */
    static String snippet(String text, String query) {
        int at = query == null || query.isBlank() ? -1 : text.toLowerCase().indexOf(query.toLowerCase());
        if (at < 0 || text.length() <= SNIPPET) {
            return text.length() > SNIPPET ? text.substring(0, SNIPPET) + "…" : text;
        }
        int start = Math.max(0, Math.min(at - SNIPPET / 3, text.length() - SNIPPET));
        int end = Math.min(text.length(), start + SNIPPET);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
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
