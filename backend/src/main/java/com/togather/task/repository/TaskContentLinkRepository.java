package com.togather.task.repository;

import com.togather.task.domain.TaskContentLink;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskContentLinkRepository
    extends JpaRepository<TaskContentLink, Long> {

    List<TaskContentLink> findAllByTaskId(Long taskId);

    boolean existsByTaskIdAndContentRefId(Long taskId, Long contentRefId);

    void deleteByTaskIdAndContentRefId(Long taskId, Long contentRefId);

    List<TaskContentLink> findAllByContentRefIdIn(
        Collection<Long> contentRefIds
    );
}
