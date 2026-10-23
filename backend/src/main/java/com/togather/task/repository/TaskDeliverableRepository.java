package com.togather.task.repository;

import com.togather.task.domain.TaskDeliverable;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskDeliverableRepository
    extends JpaRepository<TaskDeliverable, Long> {

    List<TaskDeliverable> findAllByFileIdIn(
        Collection<String> fileIds
    );
}
