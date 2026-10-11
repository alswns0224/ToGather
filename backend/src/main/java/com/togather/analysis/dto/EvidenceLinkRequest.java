package com.togather.analysis.dto;

import jakarta.validation.constraints.NotNull;

public record EvidenceLinkRequest(@NotNull Long contentRefId) {
}
