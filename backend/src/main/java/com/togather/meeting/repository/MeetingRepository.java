package com.togather.meeting.repository;

import com.togather.common.enums.MeetingStatus;
import com.togather.meeting.domain.Meeting;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Meeting> findLockedByMeetingIdAndWorkspaceId(Long meetingId, Long workspaceId);

    List<Meeting> findAllByWorkspaceIdOrderByCreatedAtDesc(Long workspaceId);

    List<Meeting> findAllByCalendarRefIdIn(Collection<Long> calendarRefIds);

    List<Meeting> findAllByMeetingIdInAndStatusNot(Collection<Long> meetingIds, MeetingStatus status);

    List<Meeting> findAllByWorkspaceIdAndStatusAndStartAtBetween(Long workspaceId, MeetingStatus status,
                                                                 java.time.LocalDateTime from,
                                                                 java.time.LocalDateTime to);
}
