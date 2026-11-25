package com.togather.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회의 생성 요청 (UI-10). 만든 사람은 참여자에 자동으로 들어간다.
 *
 * @param candidateStartAt 후보 기간 시작
 * @param candidateEndAt   후보 기간 끝
 */
public record MeetingCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @Min(15) @Max(480) int durationMinutes,
        @NotNull LocalDateTime candidateStartAt,
        @NotNull LocalDateTime candidateEndAt,
        List<Long> participantIds,
        @Size(max = 50) String meetingMode,
        List<Long> agendaTaskIds,
        @Size(max = 50) List<@NotBlank @Size(max = 200) String> agendaTitles
) {
    /** 기존 Task 안건만 보내는 호출도 그대로 지원한다. */
    public MeetingCreateRequest(String title, int durationMinutes, LocalDateTime candidateStartAt,
            LocalDateTime candidateEndAt, List<Long> participantIds, String meetingMode, List<Long> agendaTaskIds) {
        this(title, durationMinutes, candidateStartAt, candidateEndAt, participantIds, meetingMode, agendaTaskIds, null);
    }
}
