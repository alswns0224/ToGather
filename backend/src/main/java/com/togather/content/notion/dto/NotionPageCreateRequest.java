package com.togather.content.notion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param databaseId 만들 데이터베이스. 비우면 연동한 첫 번째 데이터베이스 */
public record NotionPageCreateRequest(@NotBlank @Size(max = 200) String title, @Size(max = 20000) String body,
                                      String databaseId) {
}
