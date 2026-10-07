package com.togather.meeting.domain;

import com.togather.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-18. 추천된 회의 시간 후보 */
@Getter
@Entity
@Table(name = "meeting_time_candidate")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingTimeCandidate extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long candidateId;

    private Long meetingId;

    private LocalDateTime startAt;

    /** "rank"는 PostgreSQL 함수명과 겹쳐 컬럼명을 rank_no로 둔다. */
    @Column(name = "rank_no")
    private int rank;

    private int availableCount;

    private int nonPreferredCount;

    public MeetingTimeCandidate(Long meetingId, LocalDateTime startAt, int rank, int availableCount, int nonPreferredCount) {
        this.meetingId = meetingId;
        this.startAt = startAt;
        this.rank = rank;
        this.availableCount = availableCount;
        this.nonPreferredCount = nonPreferredCount;
    }
}
