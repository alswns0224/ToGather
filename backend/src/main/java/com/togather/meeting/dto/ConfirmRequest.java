package com.togather.meeting.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record ConfirmRequest(@NotNull LocalDateTime startAt) {
}
