package com.togather.assistant.repository;

import com.togather.assistant.domain.PersonalTask;
import com.togather.common.enums.PersonalTaskStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 개인 데이터: 항상 ownerUserId 조건으로 조회한다. (README 10번) */
public interface PersonalTaskRepository extends JpaRepository<PersonalTask, Long> {

    List<PersonalTask> findAllByOwnerUserIdAndStatusOrderByDueDateAscPersonalTaskIdAsc(Long ownerUserId,
                                                                                      PersonalTaskStatus status);

    Optional<PersonalTask> findByPersonalTaskIdAndOwnerUserId(Long personalTaskId, Long ownerUserId);
}
