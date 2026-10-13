package com.togather.task.dto;

import com.togather.common.enums.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

public record TaskCreateRequest(
    @NotBlank String title,
    String description,
    Long assigneeId,
    LocalDate dueDate,
    TaskStatus status,
    List<DeliverableInput> deliverables
) {

    public TaskCommand toCommand() {
        return new TaskCommand(
            title,
            description,
            assigneeId,
            dueDate,
            status,
            deliverables,
            null
        );
    }
}
