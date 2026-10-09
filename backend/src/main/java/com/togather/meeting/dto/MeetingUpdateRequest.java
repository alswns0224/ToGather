package com.togather.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 회의 수정 요청. null인 항목은 바꾸지 않는다. 일정 조율 중(SCHEDULING)인 회의만 수정할 수 있다. */
public record MeetingUpdateRequest(
        @Size(min = 1, max = 200) String title,
        @Min(15) @Max(480) Integer durationMinutes,
        LocalDateTime candidateStartAt,
        LocalDateTime candidateEndAt,
        List<Long> participantIds,
        @Size(max = 50) String meetingMode
) {
}
