package com.togather.content.calendar.repository;

import com.togather.content.calendar.domain.CalendarEventCopy;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarEventCopyRepository extends JpaRepository<CalendarEventCopy, Long> {

    List<CalendarEventCopy> findAllByContentRefId(Long contentRefId);
}
