package com.togather.search.client;

import java.util.Collection;
import java.util.List;

/**
 * 콘텐츠 벡터 저장·유사도 검색 (C-07 searchVectors). 실패하면 BusinessException(SEARCH_UNAVAILABLE)
 * 벡터 ID는 content_ref ID, 메타데이터로 워크스페이스와 서비스를 함께 저장해 검색할 때 거른다.
 */
public interface VectorStore {

    record VectorDoc(Long contentRefId, Long workspaceId, String provider, float[] vector) {
    }

    /** distance는 코사인 거리 (0에 가까울수록 비슷하다) */
    record VectorHit(Long contentRefId, double distance) {
    }

    void upsert(List<VectorDoc> docs);

    void delete(Collection<Long> contentRefIds);

    /** 이 워크스페이스의 providers 콘텐츠 중 vector와 가까운 순서로 limit개 */
    List<VectorHit> searchVectors(float[] vector, Long workspaceId, Collection<String> providers, int limit);
}
