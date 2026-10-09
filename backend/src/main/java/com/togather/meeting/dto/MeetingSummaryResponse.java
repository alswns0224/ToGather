package com.togather.meeting.dto;

import com.togather.common.enums.MeetingStatus;
import com.togather.meeting.domain.Meeting;
import java.time.LocalDateTime;

/** 회의 목록 한 줄 */
public record MeetingSummaryResponse(
        Long meetingId,
        String title,
        MeetingStatus status,
        int durationMinutes,
        LocalDateTime candidateStartAt,
        LocalDateTime candidateEndAt,
        LocalDateTime startAt,
        String meetingMode,
        int participantCount,
        int respondedCount,
        boolean participating
) {

    public static MeetingSummaryResponse of(Meeting m, int participantCount, int respondedCount, boolean participating) {
        return new MeetingSummaryResponse(m.getMeetingId(), m.getTitle(), m.getStatus(), m.getDurationMinutes(),
                m.getCandidatePeriod().getStart(), m.getCandidatePeriod().getEnd(), m.getStartAt(), m.getMeetingMode(),
                participantCount, respondedCount, participating);
    }
}
