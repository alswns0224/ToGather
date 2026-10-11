package com.togather.content.notion.dto;

/**
 * 명세서 NotionPageRequest. 확정된 분석 결과로 문서를 만들 때 (B-08)
 *
 * @param targetId 데이터베이스 ID. null이면 연동한 첫 번째 데이터베이스
 */
public record NotionPageRequest(String title, String body, String targetId) {
}
