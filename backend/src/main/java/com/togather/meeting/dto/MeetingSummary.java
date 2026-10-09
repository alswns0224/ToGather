package com.togather.meeting.dto;

import com.togather.common.enums.MeetingStatus;
import com.togather.meeting.domain.Meeting;
import java.time.LocalDateTime;

/** 다른 파트에 제공하는 회의 요약 (docs/shared-interfaces.md, Task 협업 맥락에서 사용) */
public record MeetingSummary(Long meetingId, String title, MeetingStatus status, LocalDateTime startAt) {

    public static MeetingSummary from(Meeting meeting) {
        return new MeetingSummary(meeting.getMeetingId(), meeting.getTitle(), meeting.getStatus(), meeting.getStartAt());
    }
}
