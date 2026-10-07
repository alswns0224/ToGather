package com.togather.assistant.repository;

import com.togather.assistant.domain.PersonalTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalTaskRepository extends JpaRepository<PersonalTask, Long> {
}
