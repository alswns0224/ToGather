package com.togather.content.notion.client;

import static com.togather.common.util.JsonValues.bool;
import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Notion 블록 ↔ 텍스트 변환. ToGather에서는 본문을 간단한 마크다운 형식의 텍스트로 다룬다.
 *
 * <pre>
 * # 제목1 / ## 제목2 / ### 제목3 / - 글머리 / 1. 번호 / [ ] 할 일 / [x] 완료 / > 인용 / 그 외 문단
 * </pre>
 */
public final class NotionBlocks {

    /** Notion rich_text 한 덩어리의 최대 길이 */
    static final int MAX_TEXT_LENGTH = 2000;

    private NotionBlocks() {
    }

    /** 블록 목록을 텍스트로. 지원하지 않는 블록(이미지, 표 등)은 건너뛴다. */
    public static String toText(List<Map<String, Object>> blocks) {
        List<String> lines = new ArrayList<>();
        int number = 0;
        for (Map<String, Object> block : blocks) {
            String type = str(block, "type");
            if (type == null) {
                continue;
            }
            Map<String, Object> body = obj(block, type);
            String text = plainText(list(body, "rich_text"));
            number = "numbered_list_item".equals(type) ? number + 1 : 0;
            String line = switch (type) {
                case "heading_1" -> "# " + text;
                case "heading_2" -> "## " + text;
                case "heading_3" -> "### " + text;
                case "bulleted_list_item" -> "- " + text;
                case "numbered_list_item" -> number + ". " + text;
                case "to_do" -> (bool(body, "checked") ? "[x] " : "[ ] ") + text;
                case "quote", "callout" -> "> " + text;
                case "code" -> "```\n" + text + "\n```";
                case "paragraph", "toggle" -> text;
                case "divider" -> "---";
                default -> null;
            };
            if (line != null) {
                lines.add(line);
            }
        }
        return String.join("\n", lines).strip();
    }

    /** 텍스트를 블록 목록으로. 빈 줄은 건너뛴다. */
    public static List<Map<String, Object>> fromText(String text) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (text == null) {
            return blocks;
        }
        for (String raw : text.split("\n")) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            if (line.equals("---")) {
                blocks.add(Map.of("object", "block", "type", "divider", "divider", Map.of()));
            } else if (line.startsWith("### ")) {
                blocks.add(block("heading_3", line.substring(4)));
            } else if (line.startsWith("## ")) {
                blocks.add(block("heading_2", line.substring(3)));
            } else if (line.startsWith("# ")) {
                blocks.add(block("heading_1", line.substring(2)));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                blocks.add(block("bulleted_list_item", line.substring(2)));
            } else if (line.matches("^\\d+\\. .*")) {
                blocks.add(block("numbered_list_item", line.substring(line.indexOf(". ") + 2)));
            } else if (line.startsWith("[ ] ") || line.startsWith("[x] ")) {
                Map<String, Object> todo = block("to_do", line.substring(4));
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) todo.get("to_do");
                body.put("checked", line.startsWith("[x] "));
                blocks.add(todo);
            } else if (line.startsWith("> ")) {
                blocks.add(block("quote", line.substring(2)));
            } else {
                blocks.add(block("paragraph", line));
            }
        }
        return blocks;
    }

    public static String plainText(List<Map<String, Object>> richText) {
        return richText.stream().map(t -> str(t, "plain_text")).filter(s -> s != null)
                .collect(Collectors.joining());
    }

    /** 2,000자를 넘는 텍스트는 여러 rich_text로 나눈다. */
    static List<Map<String, Object>> richText(String text) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += MAX_TEXT_LENGTH) {
            String chunk = text.substring(i, Math.min(text.length(), i + MAX_TEXT_LENGTH));
            parts.add(Map.of("type", "text", "text", Map.of("content", chunk)));
        }
        return parts;
    }

    private static Map<String, Object> block(String type, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("rich_text", richText(text));
        Map<String, Object> block = new HashMap<>();
        block.put("object", "block");
        block.put("type", type);
        block.put(type, body);
        return block;
    }
}
