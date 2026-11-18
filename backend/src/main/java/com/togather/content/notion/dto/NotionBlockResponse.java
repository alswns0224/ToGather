package com.togather.content.notion.dto;

import java.util.List;

/**
 * 블록 단위 편집기의 블록 한 개 (페이지 최상위 블록 순서대로).
 *
 * <ul>
 *   <li>TEXT: 글 블록 한 줄 (text). 이어진 TEXT 블록은 화면에서 하나의 글 칸으로 묶는다.</li>
 *   <li>TABLE: 표 (table). 칸 수정·행 추가·행 삭제</li>
 *   <li>IMAGE: 이미지 (image). 바꾸기·캡션·삭제</li>
 *   <li>FIXED: ToGather에서 고칠 수 없는 블록 (label). 코드·토글·하위 페이지 등. 그대로 둔다.</li>
 * </ul>
 */
public record NotionBlockResponse(String id, String kind, String text, String label, TableData table,
                                  ImageData image) {

    public record TableData(int width, boolean hasColumnHeader, boolean hasRowHeader, List<Row> rows) {
    }

    public record Row(String id, List<String> cells) {
    }

    /** @param url Notion이 주는 이미지 주소 (Notion에 올린 파일이면 1시간 뒤 만료된다) */
    public record ImageData(String url, String caption) {
    }

    public static NotionBlockResponse text(String id, String text) {
        return new NotionBlockResponse(id, "TEXT", text, null, null, null);
    }

    public static NotionBlockResponse fixed(String id, String label) {
        return new NotionBlockResponse(id, "FIXED", null, label, null, null);
    }

    public static NotionBlockResponse table(String id, TableData table) {
        return new NotionBlockResponse(id, "TABLE", null, null, table, null);
    }

    public static NotionBlockResponse image(String id, ImageData image) {
        return new NotionBlockResponse(id, "IMAGE", null, null, null, image);
    }
}
