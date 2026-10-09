package com.togather.meeting.repository;

import com.togather.meeting.domain.AvailabilitySlot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, Long> {

    List<AvailabilitySlot> findAllByMeetingId(Long meetingId);

    List<AvailabilitySlot> findAllByMeetingIdAndUserId(Long meetingId, Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from AvailabilitySlot s where s.meetingId = :meetingId and s.userId = :userId")
    void deleteAllByMeetingIdAndUserId(@Param("meetingId") Long meetingId, @Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from AvailabilitySlot s where s.meetingId = :meetingId")
    void deleteAllByMeetingId(@Param("meetingId") Long meetingId);
}
