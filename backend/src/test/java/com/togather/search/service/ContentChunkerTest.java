package com.togather.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContentChunkerTest {

    @Test
    void 짧은_글은_한_조각이고_빈_글은_조각이_없다() {
        assertThat(ContentChunker.split("회의록 요약")).containsExactly("회의록 요약");
        assertThat(ContentChunker.split("   ")).isEmpty();
        assertThat(ContentChunker.split(null)).isEmpty();
    }

    @Test
    void 긴_글은_크기_제한_안에서_겹치게_나누고_모든_내용을_담는다() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("문장").append(i).append("은 여기서 끝난다. ");
        }
        String text = sb.toString().strip();

        List<String> chunks = ContentChunker.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(ContentChunker.CHUNK_SIZE));
        // 마지막 문장까지 들어 있고, 이웃한 조각은 내용이 겹친다.
        assertThat(chunks.getLast()).contains("문장399은 여기서 끝난다.");
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i - 1)).contains(chunks.get(i).substring(0, 20));
        }
        // 문장 경계에서 자른다.
        assertThat(chunks.getFirst()).endsWith(".");
    }

    @Test
    void 아주_긴_글은_최대_조각_수까지만_나눈다() {
        assertThat(ContentChunker.split("가나다라 ".repeat(50_000))).hasSize(ContentChunker.MAX_CHUNKS);
    }

    @Test
    void 같은_글은_항상_같은_조각이_나온다() {
        String text = "개요 ".repeat(3000) + "결론";
        assertThat(ContentChunker.split(text)).isEqualTo(ContentChunker.split(text));
    }
}
