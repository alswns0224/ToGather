package com.togather.meeting.dto;

import com.togather.common.enums.SlotPreference;
import com.togather.common.enums.SlotSource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 가용 시간 입력 화면 데이터 (UI-10)
 *
 * @param slotMinutes       칸 하나의 길이(분)
 * @param dayStartHour      하루 중 표시 시작 시각
 * @param dayEndHour        하루 중 표시 끝 시각
 * @param mySlots           내가 저장한 칸
 * @param busySlots         연동 캘린더에 일정이 있어 불가능으로 자동 표시할 칸
 * @param calendarConnected Calendar를 연동했는지 (연동하지 않았으면 자동 표시 없음)
 */
public record AvailabilityResponse(
        LocalDateTime candidateStartAt,
        LocalDateTime candidateEndAt,
        int slotMinutes,
        int dayStartHour,
        int dayEndHour,
        boolean hasResponded,
        List<SlotResponse> mySlots,
        List<LocalDateTime> busySlots,
        boolean calendarConnected
) {

    public record SlotResponse(LocalDateTime startAt, SlotPreference preference, SlotSource source) {
    }
}
