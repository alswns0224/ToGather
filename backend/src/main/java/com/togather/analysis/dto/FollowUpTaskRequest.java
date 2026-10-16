package com.togather.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 후속 작업 → Task 생성. 담당자·마감일은 검토 때 정한 값을 기본으로 화면에서 고칠 수 있다. */
public record FollowUpTaskRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2000) String description,
                                  @NotNull Long assigneeId, @NotNull LocalDate dueDate) {
}
