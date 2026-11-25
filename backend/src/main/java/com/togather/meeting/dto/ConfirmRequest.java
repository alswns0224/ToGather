package com.togather.meeting.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * @param force 참여자 중 이 시간에 다른 일정이 있어도 확정할지. 비우면 겹치는 사람이 있을 때 확정하지 않는다.
 */
public record ConfirmRequest(@NotNull LocalDateTime startAt, Boolean force) {
}
