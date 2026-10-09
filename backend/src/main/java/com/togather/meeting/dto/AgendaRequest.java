package com.togather.meeting.dto;

import jakarta.validation.constraints.NotNull;

public record AgendaRequest(@NotNull Long taskId) {
}
