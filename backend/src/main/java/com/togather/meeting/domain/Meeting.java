package com.togather.meeting.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.domain.DateRange;
import com.togather.common.enums.MeetingStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-14 */
@Getter
@Entity
@Table(name = "meeting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Meeting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long meetingId;

    private Long workspaceId;

    private String title;

    private int durationMinutes;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "start", column = @Column(name = "candidate_start_at")),
            @AttributeOverride(name = "end", column = @Column(name = "candidate_end_at"))
    })
    private DateRange candidatePeriod;

    /** 진행 방식. 값 목록 미정이라 String (예: 온라인, 오프라인) */
    private String meetingMode;

    @Enumerated(EnumType.STRING)
    private MeetingStatus status;

    private LocalDateTime startAt;

    private Long calendarRefId;

    private boolean calendarSyncFailed;

    /** 회의를 만든 사용자 (V3) */
    private Long createdBy;

    /** 확정 후 등록된 화상 회의 링크 (V3) */
    private String meetLink;

    public Meeting(Long workspaceId, Long createdBy, String title, int durationMinutes, DateRange candidatePeriod,
                   String meetingMode) {
        this.workspaceId = workspaceId;
        this.createdBy = createdBy;
        this.title = title;
        this.durationMinutes = durationMinutes;
        this.candidatePeriod = candidatePeriod;
        this.meetingMode = meetingMode;
        this.status = MeetingStatus.SCHEDULING;
    }

    public void update(String title, int durationMinutes, DateRange candidatePeriod, String meetingMode) {
        this.title = title;
        this.durationMinutes = durationMinutes;
        this.candidatePeriod = candidatePeriod;
        this.meetingMode = meetingMode;
    }

    public void cancel() {
        this.status = MeetingStatus.CANCELED;
    }

    public void confirm(LocalDateTime startAt) {
        this.startAt = startAt;
        this.status = MeetingStatus.CONFIRMED;
    }

    public LocalDateTime getEndAt() {
        return startAt == null ? null : startAt.plusMinutes(durationMinutes);
    }

    public boolean isScheduling() {
        return status == MeetingStatus.SCHEDULING;
    }

    public void registerCalendarEvent(Long contentRefId, String meetLink) {
        this.calendarRefId = contentRefId;
        this.meetLink = meetLink;
        this.calendarSyncFailed = false;
    }

    public void markCalendarSyncFailed() {
        this.calendarSyncFailed = true;
    }
}
