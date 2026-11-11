package com.togather.search.client;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.search.config.SearchProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Chroma 서버 REST API (v2) 클라이언트.
 * 실행: pip install chromadb → chroma run --path ./chroma-data (기본 localhost:8000)
 * 컬렉션은 처음 쓸 때 코사인 거리로 만든다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChromaVectorStore implements VectorStore {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final String COLLECTIONS = "/api/v2/tenants/default_tenant/databases/default_database/collections";
    private static final String ID_PREFIX = "cr-";

    private final RestClient searchRestClient;
    private final SearchProperties properties;
    private volatile String collectionId;

    @Override
    public void upsert(List<VectorDoc> docs) {
        if (docs.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        List<float[]> embeddings = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();
        for (VectorDoc doc : docs) {
            ids.add(ID_PREFIX + doc.contentRefId());
            embeddings.add(doc.vector());
            metadatas.add(Map.of("workspaceId", doc.workspaceId(), "provider", doc.provider(),
                    "contentRefId", doc.contentRefId()));
        }
        post("upsert", Map.of("ids", ids, "embeddings", embeddings, "metadatas", metadatas));
    }

    @Override
    public void delete(Collection<Long> contentRefIds) {
        if (contentRefIds.isEmpty()) {
            return;
        }
        post("delete", Map.of("ids", contentRefIds.stream().map(id -> ID_PREFIX + id).toList()));
    }

    @Override
    public List<VectorHit> searchVectors(float[] vector, Long workspaceId, Collection<String> providers, int limit) {
        Map<String, Object> where = Map.of("$and", List.of(
                Map.of("workspaceId", Map.of("$eq", workspaceId)),
                Map.of("provider", Map.of("$in", List.copyOf(providers)))));
        Map<String, Object> body = new HashMap<>();
        body.put("query_embeddings", List.of(vector));
        body.put("n_results", limit);
        body.put("where", where);
        body.put("include", List.of("distances"));
        Map<String, Object> json = post("query", body);
        List<String> ids = firstRow(json, "ids");
        List<Number> distances = firstRow(json, "distances");
        List<VectorHit> hits = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id.startsWith(ID_PREFIX) && i < distances.size()) {
                hits.add(new VectorHit(Long.parseLong(id.substring(ID_PREFIX.length())),
                        distances.get(i).doubleValue()));
            }
        }
        return hits;
    }

    // ===================== 내부 =====================

    /** 컬렉션이 지워졌으면(404) ID를 다시 받아 한 번 더 시도한다. */
    private Map<String, Object> post(String action, Map<String, Object> body) {
        try {
            return call(action, body);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw unavailable(action, e);
            }
            collectionId = null;
            try {
                return call(action, body);
            } catch (RestClientException retry) {
                throw unavailable(action, retry);
            }
        } catch (RestClientException e) {
            throw unavailable(action, e);
        }
    }

    private Map<String, Object> call(String action, Map<String, Object> body) {
        Map<String, Object> json = searchRestClient.post()
                .uri(properties.chromaUrl() + COLLECTIONS + "/" + collectionId() + "/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JSON);
        return json == null ? Map.of() : json;
    }

    private String collectionId() {
        String id = collectionId;
        if (id != null) {
            return id;
        }
        Map<String, Object> json = searchRestClient.post()
                .uri(properties.chromaUrl() + COLLECTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", properties.collection(), "get_or_create", true,
                        "metadata", Map.of("hnsw:space", "cosine")))
                .retrieve()
                .body(JSON);
        if (json == null || json.get("id") == null) {
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
        }
        collectionId = String.valueOf(json.get("id"));
        return collectionId;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> firstRow(Map<String, Object> json, String key) {
        Object rows = json.get(key);
        if (rows instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof List<?> first) {
            return (List<T>) first;
        }
        return List.of();
    }

    private BusinessException unavailable(String action, RestClientException e) {
        log.warn("Chroma {} 실패: {}", action, e.getClass().getSimpleName());
        return new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
    }
}
