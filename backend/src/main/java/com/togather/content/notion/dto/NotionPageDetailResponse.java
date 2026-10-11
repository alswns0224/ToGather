package com.togather.content.notion.dto;

import java.time.LocalDateTime;

/**
 * UI-05 문서 본문과 원본 바로가기
 *
 * @param body          간단한 마크다운 형식의 본문 (# 제목, - 목록, [ ] 할 일 ...)
 * @param refreshFailed Notion에서 최신 본문을 가져오지 못해 저장된 내용을 보여주는 경우
 */
public record NotionPageDetailResponse(Long contentRefId, String pageId, String title, String body, String deepLink,
                                       LocalDateTime lastModifiedAt, boolean fromAnalysis, boolean isOriginDeleted,
                                       boolean refreshFailed) {
}
