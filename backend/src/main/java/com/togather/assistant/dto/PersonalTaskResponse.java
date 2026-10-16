package com.togather.assistant.dto;

import com.togather.common.enums.PersonalTaskStatus;
import java.time.LocalDate;

public record PersonalTaskResponse(Long personalTaskId, String title, LocalDate dueDate, PersonalTaskStatus status,
                                   Long sourceContentRefId) {
}
