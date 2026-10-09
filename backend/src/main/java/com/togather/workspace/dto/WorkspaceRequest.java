package com.togather.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 워크스페이스 생성·수정 요청 */
public record WorkspaceRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description
) {
}
