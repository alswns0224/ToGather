package com.togather.content.notion.service;

import static com.togather.common.util.JsonValues.bool;
import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.notion.client.NotionApiClient;
import com.togather.content.notion.client.NotionBlocks;
import com.togather.content.notion.dto.NotionBlockResponse;
import com.togather.content.notion.dto.NotionBlockResponse.ImageData;
import com.togather.content.notion.dto.NotionBlockResponse.Row;
import com.togather.content.notion.dto.NotionBlockResponse.TableData;
import com.togather.content.notion.dto.NotionBlocksSaveRequest.Item;
import com.togather.content.notion.dto.NotionBlocksSaveRequest.TableRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이미지·표가 있는 문서도 ToGather에서 고칠 수 있게 블록 단위로 읽고 저장한다.
 *
 * <p>저장 원칙: 고친 부분만 바꾸고 나머지 블록은 건드리지 않는다.
 * <ul>
 *   <li>글: 바뀐 글 묶음만, 새 블록을 원래 자리에 먼저 넣고 옛 블록을 지운다. (중간에 실패해도 내용이 사라지지 않음)</li>
 *   <li>표: 바뀐 행만 칸을 고치고, 새 행은 맨 아래에 붙이고, 지운 행만 지운다. (열 개수는 그대로)</li>
 *   <li>이미지: 바꾸기·캡션 수정·삭제·새로 넣기</li>
 *   <li>코드·토글·하위 페이지 등 고칠 수 없는 블록은 그대로 두고 위치 기준으로만 쓴다.</li>
 * </ul>
 * 새 블록은 "바로 앞 블록 뒤에" 넣는다. 문서 맨 앞에 넣을 기준 블록이 없을 때만 제한이 있다. (아래 save 참고)
 */
@Component
@RequiredArgsConstructor
public class NotionBlockEditor {

    private final NotionApiClient notionApiClient;

    public List<NotionBlockResponse> read(String token, String pageId) {
        List<NotionBlockResponse> result = new ArrayList<>();
        int number = 0;
        for (Map<String, Object> block : notionApiClient.getBlocks(token, pageId)) {
            if (bool(block, "archived") || bool(block, "in_trash")) {
                continue;
            }
            String id = str(block, "id");
            String type = str(block, "type");
            number = "numbered_list_item".equals(type) && NotionBlocks.isTextBlock(block) ? number + 1 : 0;
            if (NotionBlocks.isTextBlock(block)) {
                result.add(NotionBlockResponse.text(id, NotionBlocks.lineOf(block, number)));
            } else if ("image".equals(type)) {
                result.add(NotionBlockResponse.image(id, image(obj(block, "image"))));
            } else if ("table".equals(type)) {
                result.add(NotionBlockResponse.table(id, table(token, id, obj(block, "table"))));
            } else {
                result.add(NotionBlockResponse.fixed(id, NotionBlocks.fixedLabel(block)));
            }
        }
        return result;
    }

    private static ImageData image(Map<String, Object> image) {
        String source = str(image, "type");
        String url = source == null ? null : str(obj(image, source), "url");
        return new ImageData(url, NotionBlocks.plainText(list(image, "caption")));
    }

    private TableData table(String token, String tableId, Map<String, Object> table) {
        int width = table.get("table_width") instanceof Number n ? n.intValue() : 0;
        List<Row> rows = notionApiClient.getBlocks(token, tableId).stream()
                .filter(r -> "table_row".equals(str(r, "type")))
                .map(r -> new Row(str(r, "id"), cellTexts(obj(r, "table_row"))))
                .toList();
        return new TableData(width, bool(table, "has_column_header"), bool(table, "has_row_header"), rows);
    }

    @SuppressWarnings("unchecked")
    private static List<String> cellTexts(Map<String, Object> row) {
        Object cells = row.get("cells");
        if (!(cells instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(cell -> cell instanceof List<?> rich ? NotionBlocks.plainText((List<Map<String, Object>>) rich) : "")
                .toList();
    }

    /**
     * 편집이 끝난 문서(items, 위에서부터 순서대로)를 Notion에 반영한다.
     * 앞에서부터 "마지막으로 자리가 정해진 블록(cursor)"을 따라가며, 새 블록은 그 뒤에 넣는다.
     * 맨 앞이라 cursor가 없을 때: 원래 글이 있었으면 그 첫 블록 뒤에 넣고 원래 글을 지운다.
     * 원래 블록이 하나도 없는 맨 앞에는 뒤에 블록이 남아 있으면 넣을 수 없다. (화면에서 그런 칸은 주지 않는다)
     */
    public void save(String token, String pageId, List<Item> items) {
        boolean[] existingAfter = new boolean[items.size() + 1];
        for (int i = items.size() - 1; i >= 0; i--) {
            existingAfter[i] = existingAfter[i + 1] || hasExistingBlock(items.get(i));
        }
        String cursor = null;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean blocksAfter = existingAfter[i + 1];
            cursor = switch (item.kind()) {
                case "TEXT" -> saveText(token, pageId, item, cursor, blocksAfter);
                case "TABLE" -> {
                    saveTable(token, item);
                    yield item.id();
                }
                case "IMAGE" -> saveImage(token, pageId, item, cursor, blocksAfter);
                default -> item.id(); // FIXED
            };
        }
    }

    private static boolean hasExistingBlock(Item item) {
        return switch (item.kind()) {
            case "TEXT" -> item.blockIds() != null && !item.blockIds().isEmpty();
            case "IMAGE" -> item.id() != null && !item.isDeleted();
            default -> item.id() != null;
        };
    }

    /** @return 이 칸 다음의 cursor */
    private String saveText(String token, String pageId, Item item, String cursor, boolean blocksAfter) {
        List<String> old = item.blockIds() == null ? List.of() : item.blockIds();
        if (!item.isChanged()) {
            return old.isEmpty() ? cursor : old.getLast();
        }
        List<Map<String, Object>> blocks = NotionBlocks.fromText(item.text());
        List<String> created;
        if (cursor != null) {
            created = notionApiClient.insertAfter(token, pageId, cursor, blocks);
        } else if (!old.isEmpty()) {
            created = notionApiClient.insertAfter(token, pageId, old.getFirst(), blocks);
        } else if (!blocksAfter) {
            created = notionApiClient.appendBlocks(token, pageId, blocks);
        } else if (blocks.isEmpty()) {
            created = List.of();
        } else {
            // 문서 맨 앞, 기준 블록이 없는 자리 (Notion API로 맨 앞에 넣을 수 없음)
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        old.forEach(id -> notionApiClient.deleteBlock(token, id));
        return created.isEmpty() ? cursor : created.getLast();
    }

    private void saveTable(String token, Item item) {
        int width = item.width() == null ? 0 : item.width();
        if (item.id() == null || width <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        List<List<String>> newRows = new ArrayList<>();
        for (TableRow row : item.rows() == null ? List.<TableRow>of() : item.rows()) {
            if (row.id() == null) {
                newRows.add(row.cells());
            } else if (row.isChanged()) {
                notionApiClient.updateTableRow(token, row.id(), row.cells(), width);
            }
        }
        if (!newRows.isEmpty()) {
            notionApiClient.appendTableRows(token, item.id(), newRows, width);
        }
        for (String rowId : item.deletedRowIds() == null ? List.<String>of() : item.deletedRowIds()) {
            notionApiClient.deleteBlock(token, rowId);
        }
    }

    /** @return 이 칸 다음의 cursor */
    private String saveImage(String token, String pageId, Item item, String cursor, boolean blocksAfter) {
        if (item.isDeleted()) {
            if (item.id() != null) {
                notionApiClient.deleteBlock(token, item.id());
            }
            return cursor;
        }
        Map<String, Object> image = NotionApiClient.imageBody(item.url(), item.fileUploadId(), item.caption());
        if (item.id() != null) {
            if (item.isChanged()) {
                notionApiClient.updateImage(token, item.id(), image);
            }
            return item.id();
        }
        if (!image.containsKey("type")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT); // 새 이미지인데 파일·주소가 없음
        }
        if (cursor == null && blocksAfter) {
            throw new BusinessException(ErrorCode.INVALID_INPUT); // 문서 맨 앞에는 넣을 수 없음
        }
        return notionApiClient.insertImage(token, pageId, cursor, image);
    }
}
