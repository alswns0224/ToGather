package com.togather.integration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 연동 대상 선택. 기존 선택은 모두 바뀐다. */
public record TargetSelectRequest(@NotEmpty List<@Valid Target> targets) {

    public record Target(@NotBlank String id, String name) {
    }
}
