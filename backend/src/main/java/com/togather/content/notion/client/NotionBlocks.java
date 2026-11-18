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

    /** ToGather에서 텍스트로 바꿨다가 그대로 되돌릴 수 있는 블록 */
    static final java.util.Set<String> EDITABLE_TYPES = java.util.Set.of(
            "paragraph", "heading_1", "heading_2", "heading_3", "bulleted_list_item", "numbered_list_item",
            "to_do", "quote", "divider");

    /**
     * ToGather에서 본문을 수정해도 내용이 사라지지 않는지.
     * 이미지·표·코드·토글 같은 블록이나 하위 블록(들여쓴 목록 등)이 있으면 수정할 때 지워지므로 false
     */
    public static boolean isEditable(List<Map<String, Object>> blocks) {
        return blocks.stream().allMatch(b -> EDITABLE_TYPES.contains(str(b, "type")) && !bool(b, "has_children"));
    }

    /** ToGather 편집기에서 글로 고칠 수 있는 블록 (하위 블록이 없는 문단·제목·목록·할 일·인용·구분선) */
    public static boolean isTextBlock(Map<String, Object> block) {
        return EDITABLE_TYPES.contains(str(block, "type")) && !bool(block, "has_children");
    }

    /**
     * 글 블록 한 개를 편집기 한 줄로. (fromText로 되돌릴 수 있는 형식)
     *
     * @param number 번호 목록이면 이 블록의 번호
     */
    public static String lineOf(Map<String, Object> block, int number) {
        String type = str(block, "type");
        Map<String, Object> body = obj(block, type);
        String text = plainText(list(body, "rich_text"));
        return switch (type) {
            case "heading_1" -> "# " + text;
            case "heading_2" -> "## " + text;
            case "heading_3" -> "### " + text;
            case "bulleted_list_item" -> "- " + text;
            case "numbered_list_item" -> number + ". " + text;
            case "to_do" -> (bool(body, "checked") ? "[x] " : "[ ] ") + text;
            case "quote" -> "> " + text;
            case "divider" -> "---";
            default -> text;
        };
    }

    /** 편집기에서 고칠 수 없는 블록의 이름 (화면에 자리 표시로 보여준다) */
    public static String fixedLabel(Map<String, Object> block) {
        String type = str(block, "type");
        Map<String, Object> body = obj(block, type);
        String text = body.get("rich_text") instanceof List<?> ? plainText(list(body, "rich_text")) : "";
        String name = switch (type == null ? "" : type) {
            case "code" -> "코드";
            case "toggle" -> "토글";
            case "callout" -> "콜아웃";
            case "child_page" -> "하위 페이지: " + str(body, "title");
            case "child_database" -> "데이터베이스: " + str(body, "title");
            case "column_list" -> "여러 열";
            case "synced_block" -> "동기화 블록";
            case "bookmark", "link_preview", "embed" -> "링크";
            case "video" -> "동영상";
            case "file", "pdf", "audio" -> "첨부 파일";
            case "equation" -> "수식";
            case "table_of_contents" -> "목차";
            default -> bool(block, "has_children") ? "하위 항목이 있는 블록" : "Notion 블록";
        };
        return text.isBlank() ? name : name + " · " + text;
    }

    /** 표 칸 한 개를 rich_text로 */
    static List<List<Map<String, Object>>> cells(List<String> values, int width) {
        List<List<Map<String, Object>>> cells = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            String value = values != null && i < values.size() && values.get(i) != null ? values.get(i) : "";
            cells.add(value.isEmpty() ? List.of() : richText(value));
        }
        return cells;
    }

    /** 블록 목록을 텍스트로. 지원하지 않는 블록(이미지, 표 등)은 건너뛴다. */
    public static String toText(List<Map<String, Object>> blocks) {
        return toText(blocks, Map.of());
    }

    /**
     * 블록 목록을 텍스트로. 표는 tableRows(표 블록 ID → 행 블록)가 있으면 "| 칸 | 칸 |" 줄로,
     * 이미지는 "[이미지: 파일 이름] 캡션" 줄로 넣는다. (이미지를 바꾸면 파일 이름이 바뀌어 변경으로 잡힌다)
     */
    public static String toText(List<Map<String, Object>> blocks, Map<String, List<Map<String, Object>>> tableRows) {
        List<String> lines = new ArrayList<>();
        int number = 0;
        for (Map<String, Object> block : blocks) {
            String type = str(block, "type");
            if (type == null) {
                continue;
            }
            Map<String, Object> body = obj(block, type);
            if ("table".equals(type)) {
                for (Map<String, Object> row : tableRows.getOrDefault(str(block, "id"), List.of())) {
                    Object cells = obj(row, "table_row").get("cells");
                    if (cells instanceof List<?> list) {
                        @SuppressWarnings("unchecked")
                        List<String> texts = list.stream()
                                .map(c -> c instanceof List<?> rich ? plainText((List<Map<String, Object>>) rich) : "")
                                .toList();
                        lines.add("| " + String.join(" | ", texts) + " |");
                    }
                }
                number = 0;
                continue;
            }
            if ("image".equals(type)) {
                String caption = plainText(list(body, "caption"));
                lines.add(("[이미지: " + imageName(body) + "] " + caption).strip());
                number = 0;
                continue;
            }
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

    /** 이미지 파일 이름. Notion에 올린 파일 주소는 조회할 때마다 서명(? 뒤)이 바뀌므로 경로의 마지막 부분만 쓴다. */
    static String imageName(Map<String, Object> image) {
        String source = str(image, "type");
        String url = source == null ? null : str(obj(image, source), "url");
        if (url == null) {
            return "";
        }
        String path = url.split("\\?", 2)[0];
        return path.substring(path.lastIndexOf('/') + 1);
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
