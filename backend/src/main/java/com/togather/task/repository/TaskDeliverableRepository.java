package com.togather.task.repository;

import com.togather.task.domain.TaskDeliverable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskDeliverableRepository extends JpaRepository<TaskDeliverable, Long> {
}
