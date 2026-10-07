package com.togather.meeting.repository;

import com.togather.meeting.domain.AvailabilitySlot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, Long> {
}
