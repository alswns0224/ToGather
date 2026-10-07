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

/** C-00-16 */
@Getter
@Entity
@Table(name = "meeting_agenda")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingAgenda extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long agendaId;

    private Long meetingId;

    private Long taskId;

    private int orderNo;

    public MeetingAgenda(Long meetingId, Long taskId, int orderNo) {
        this.meetingId = meetingId;
        this.taskId = taskId;
        this.orderNo = orderNo;
    }

    public void changeTask(Long taskId) {
        this.taskId = taskId;
    }
}
