package com.togather.task.repository;

import com.togather.task.domain.TaskContentLink;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskContentLinkRepository
    extends JpaRepository<TaskContentLink, Long> {

    List<TaskContentLink> findAllByTaskId(Long taskId);

    List<TaskContentLink> findAllByContentRefIdIn(
        Collection<Long> contentRefIds
    );
}
