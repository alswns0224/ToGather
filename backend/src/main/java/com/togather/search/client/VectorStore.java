package com.togather.search.client;

import java.util.Collection;
import java.util.List;

/**
 * 콘텐츠 벡터 저장·유사도 검색 (C-07 searchVectors). 실패하면 BusinessException(SEARCH_UNAVAILABLE)
 * 긴 콘텐츠는 여러 조각으로 나눠 저장한다. 벡터 ID는 "content_ref ID-조각 번호"이고,
 * 메타데이터로 content_ref ID·워크스페이스·서비스를 함께 저장해 검색할 때 거르고 콘텐츠 단위로 지운다.
 */
public interface VectorStore {

    /** @param chunk 조각 번호 (0부터) */
    record VectorDoc(Long contentRefId, int chunk, Long workspaceId, String provider, float[] vector) {

        public VectorDoc(Long contentRefId, Long workspaceId, String provider, float[] vector) {
            this(contentRefId, 0, workspaceId, provider, vector);
        }
    }

    /** distance는 코사인 거리 (0에 가까울수록 비슷하다). chunk는 가장 가까운 조각 번호 */
    record VectorHit(Long contentRefId, int chunk, double distance) {

        public VectorHit(Long contentRefId, double distance) {
            this(contentRefId, 0, distance);
        }
    }

    void upsert(List<VectorDoc> docs);

    /** 콘텐츠의 모든 조각을 지운다. */
    void delete(Collection<Long> contentRefIds);

    /** 이 워크스페이스의 providers 조각 중 vector와 가까운 순서로 limit개 (같은 콘텐츠의 조각이 여러 개 나올 수 있다) */
    List<VectorHit> searchVectors(float[] vector, Long workspaceId, Collection<String> providers, int limit);
}
