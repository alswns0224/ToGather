package com.togather.content.notion.dto;

import java.time.LocalDateTime;

/**
 * UI-05 문서 목록 한 줄
 *
 * @param fromAnalysis 결정사항 정리(B-08)로 만든 문서인지 (구분 표시)
 */
public record NotionPageResponse(Long contentRefId, String pageId, String databaseId, String title,
                                 LocalDateTime lastModifiedAt, String deepLink, boolean fromAnalysis,
                                 boolean isOriginDeleted, boolean syncFailed) {
}
