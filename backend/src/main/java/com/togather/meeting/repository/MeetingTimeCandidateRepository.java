package com.togather.meeting.repository;

import com.togather.meeting.domain.MeetingTimeCandidate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingTimeCandidateRepository extends JpaRepository<MeetingTimeCandidate, Long> {

    List<MeetingTimeCandidate> findAllByMeetingIdOrderByRankAsc(Long meetingId);

    @Modifying(flushAutomatically = true)
    @Query("delete from MeetingTimeCandidate c where c.meetingId = :meetingId")
    void deleteAllByMeetingId(@Param("meetingId") Long meetingId);
}
