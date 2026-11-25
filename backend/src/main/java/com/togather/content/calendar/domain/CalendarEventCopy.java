package com.togather.content.calendar.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 팀 일정을 참여자 캘린더에 넣은 사본. 참여자가 초대를 수락하지 않아도 본인 캘린더에 회의가 보이게 한다. */
@Getter
@Entity
@Table(name = "calendar_event_copy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalendarEventCopy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long copyId;

    /** 원본 팀 일정 (content_ref) */
    private Long contentRefId;

    private Long userId;

    private String calendarId;

    private String eventId;

    private LocalDateTime createdAt;

    public CalendarEventCopy(Long contentRefId, Long userId, String calendarId, String eventId) {
        this.contentRefId = contentRefId;
        this.userId = userId;
        this.calendarId = calendarId;
        this.eventId = eventId;
        this.createdAt = LocalDateTime.now();
    }
}
