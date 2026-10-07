package com.togather.meeting.repository;

import com.togather.meeting.domain.MeetingTimeCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingTimeCandidateRepository extends JpaRepository<MeetingTimeCandidate, Long> {
}
