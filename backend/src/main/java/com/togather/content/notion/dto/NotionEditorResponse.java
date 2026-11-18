package com.togather.content.notion.dto;

import java.util.List;

/** 블록 단위 편집기를 열 때 받는 제목과 블록 목록 */
public record NotionEditorResponse(String title, List<NotionBlockResponse> blocks) {
}
