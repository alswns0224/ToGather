package com.togather.meeting.repository;

import com.togather.common.enums.MeetingStatus;
import com.togather.meeting.domain.Meeting;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    List<Meeting> findAllByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    List<Meeting> findAllByCalendarRefIdIn(Collection<Long> calendarRefIds);

    List<Meeting> findAllByMeetingIdInAndStatusNot(Collection<Long> meetingIds, MeetingStatus status);
}
