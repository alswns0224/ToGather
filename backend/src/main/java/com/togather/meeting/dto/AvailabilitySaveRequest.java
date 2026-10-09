package com.togather.meeting.dto;

import com.togather.common.enums.SlotPreference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 가용 시간 저장. 칸(30분) 단위로 보낸다. 보내지 않은 칸은 "불가능"으로 본다.
 * 연동 캘린더에 일정이 있는 칸은 보낸 값과 관계없이 불가능(CALENDAR_AUTO)으로 저장된다.
 */
public record AvailabilitySaveRequest(@NotNull List<@Valid SlotInput> slots) {

    public record SlotInput(@NotNull LocalDateTime startAt, @NotNull SlotPreference preference) {
    }
}
