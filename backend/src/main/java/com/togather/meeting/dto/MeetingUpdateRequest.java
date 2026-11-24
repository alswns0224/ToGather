package com.togather.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** null은 유지한다. 확정 회의는 startAt을 수정하며, candidateStartAt/EndAt은 조율 중에만 변경한다. */
public record MeetingUpdateRequest(
        @Size(min = 1, max = 200) @Pattern(regexp = "(?s).*\\S.*") String title,
        @Min(15) @Max(480) Integer durationMinutes,
        LocalDateTime candidateStartAt,
        LocalDateTime candidateEndAt,
        List<Long> participantIds,
        @Size(max = 50) String meetingMode,
        LocalDateTime startAt
) {
}
