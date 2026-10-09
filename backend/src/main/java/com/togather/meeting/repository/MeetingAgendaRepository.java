package com.togather.meeting.repository;

import com.togather.meeting.domain.MeetingAgenda;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingAgendaRepository extends JpaRepository<MeetingAgenda, Long> {

    List<MeetingAgenda> findAllByMeetingIdOrderByOrderNoAsc(Long meetingId);

    List<MeetingAgenda> findAllByTaskId(Long taskId);

    boolean existsByMeetingIdAndTaskId(Long meetingId, Long taskId);
}
