package com.togather.search.client;

import java.util.List;

/** 글을 검색용 벡터로 바꾼다. (C-07) 실패하면 BusinessException(SEARCH_UNAVAILABLE) */
public interface EmbeddingClient {

    List<float[]> embedAll(List<String> texts);

    default float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }
}
