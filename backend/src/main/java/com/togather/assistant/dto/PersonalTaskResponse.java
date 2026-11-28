package com.togather.assistant.dto;

import com.togather.common.enums.PersonalTaskStatus;
import java.time.LocalDate;
import java.time.LocalTime;

public record PersonalTaskResponse(Long personalTaskId, String title, LocalDate dueDate, PersonalTaskStatus status,
                                   Long sourceContentRefId, LocalTime dueTime) {

    public PersonalTaskResponse(Long personalTaskId, String title, LocalDate dueDate, PersonalTaskStatus status,
                                Long sourceContentRefId) {
        this(personalTaskId, title, dueDate, status, sourceContentRefId, null);
    }
}
