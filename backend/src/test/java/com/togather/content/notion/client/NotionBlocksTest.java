package com.togather.content.notion.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotionBlocksTest {

    @Test
    void 텍스트를_블록으로_바꿨다가_다시_텍스트로_되돌린다() {
        String text = """
                # 결정사항
                - 발표는 목요일
                1. 자료 조사
                2. 슬라이드
                [ ] 회의록 공유
                [x] 주제 확정
                > 근거: 철준 발언
                일반 문단
                """.strip();

        List<Map<String, Object>> blocks = NotionBlocks.fromText(text);
        // Notion 응답처럼 plain_text를 채운다.
        List<Map<String, Object>> fromApi = blocks.stream().map(NotionBlocksTest::asApiResponse).toList();

        assertThat(blocks).extracting(b -> b.get("type")).containsExactly("heading_1", "bulleted_list_item",
                "numbered_list_item", "numbered_list_item", "to_do", "to_do", "quote", "paragraph");
        assertThat(NotionBlocks.toText(fromApi)).isEqualTo(text);
    }

    @Test
    void 긴_문단은_2000자씩_나눈다() {
        assertThat(NotionBlocks.richText("가".repeat(4500))).hasSize(3);
    }

    @Test
    void 지원하지_않는_블록이나_하위_블록이_있으면_수정할_수_없다() {
        assertThat(NotionBlocks.isEditable(List.of(Map.of("type", "paragraph"), Map.of("type", "to_do")))).isTrue();
        assertThat(NotionBlocks.isEditable(List.of(Map.of("type", "paragraph"), Map.of("type", "image")))).isFalse();
        assertThat(NotionBlocks.isEditable(List.of(Map.of("type", "bulleted_list_item", "has_children", true))))
                .isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asApiResponse(Map<String, Object> block) {
        String type = (String) block.get("type");
        Map<String, Object> body = new java.util.HashMap<>((Map<String, Object>) block.get(type));
        List<Map<String, Object>> rich = (List<Map<String, Object>>) body.getOrDefault("rich_text", List.of());
        body.put("rich_text", rich.stream()
                .map(r -> Map.<String, Object>of("plain_text", ((Map<String, Object>) r.get("text")).get("content")))
                .toList());
        return Map.of("type", type, type, body);
    }
}
