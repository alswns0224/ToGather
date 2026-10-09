package com.togather.meeting.domain;

import com.togather.common.domain.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-15. 대리키 participantId는 구현 편의를 위해 추가 (meeting_id + user_id는 UNIQUE) */
@Getter
@Entity
@Table(name = "meeting_participant")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingParticipant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long participantId;

    private Long meetingId;

    private Long userId;

    private boolean hasResponded;

    public MeetingParticipant(Long meetingId, Long userId) {
        this.meetingId = meetingId;
        this.userId = userId;
    }

    public void markResponded() {
        this.hasResponded = true;
    }

    /** 후보 기간이 바뀌면 다시 응답해야 한다. */
    public void resetResponse() {
        this.hasResponded = false;
    }
}
