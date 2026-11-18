package com.togather.content.notion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 블록 단위 편집 저장. items는 편집이 끝난 문서를 위에서부터 순서대로 담는다.
 *
 * @param title null이면 제목은 그대로
 */
public record NotionBlocksSaveRequest(@Size(min = 1, max = 200) String title,
                                      @NotNull @Size(max = 500) List<@Valid Item> items) {

    /**
     * 편집기의 칸 하나.
     *
     * <ul>
     *   <li>TEXT: 이어진 글 블록 묶음. blockIds = 원래 블록들, text = 고친 글(한 줄에 블록 하나), changed</li>
     *   <li>FIXED: 고칠 수 없는 블록. id만 (위치 기준으로 쓴다)</li>
     *   <li>TABLE: id, rows(고친 행: id가 null이면 새 행), deletedRowIds, width</li>
     *   <li>IMAGE: id(null이면 새 이미지), fileUploadId 또는 url, caption, changed, deleted</li>
     * </ul>
     */
    public record Item(@NotNull @Pattern(regexp = "TEXT|FIXED|TABLE|IMAGE") String kind,
                       String id,
                       @Size(max = 300) List<String> blockIds,
                       @Size(max = 20000) String text,
                       Boolean changed,
                       Integer width,
                       @Size(max = 300) List<@Valid TableRow> rows,
                       @Size(max = 300) List<String> deletedRowIds,
                       @Size(max = 2000) String url,
                       String fileUploadId,
                       @Size(max = 2000) String caption,
                       Boolean deleted) {

        /** 화면이 보내지 않았으면 false */
        public boolean isChanged() {
            return Boolean.TRUE.equals(changed);
        }

        public boolean isDeleted() {
            return Boolean.TRUE.equals(deleted);
        }
    }

    /** @param changed 기존 행이면 칸을 바꿨는지 (바뀐 행만 Notion에 보낸다) */
    public record TableRow(String id, @Size(max = 100) List<@Size(max = 2000) String> cells, Boolean changed) {

        public boolean isChanged() {
            return Boolean.TRUE.equals(changed);
        }
    }
}
