package com.togather.meeting.repository;

import com.togather.meeting.domain.MeetingAgenda;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingAgendaRepository extends JpaRepository<MeetingAgenda, Long> {
}
