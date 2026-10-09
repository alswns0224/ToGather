package com.togather.meeting.repository;

import com.togather.meeting.domain.MeetingParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, Long> {

    List<MeetingParticipant> findAllByMeetingId(Long meetingId);

    List<MeetingParticipant> findAllByMeetingIdIn(List<Long> meetingIds);

    Optional<MeetingParticipant> findByMeetingIdAndUserId(Long meetingId, Long userId);

    boolean existsByMeetingIdAndUserId(Long meetingId, Long userId);
}
