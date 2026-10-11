package com.togather.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param databaseId 비우면 연동한 첫 번째 데이터베이스 */
public record NotionOutputRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 20000) String body,
                                  String databaseId) {
}
