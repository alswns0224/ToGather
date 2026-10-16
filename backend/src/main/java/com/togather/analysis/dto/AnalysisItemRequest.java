package com.togather.analysis.dto;

import com.togather.common.enums.ItemType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 항목 추가 (빈 양식에서 직접 작성 포함) */
public record AnalysisItemRequest(@NotNull ItemType itemType, @NotBlank @Size(max = 2000) String content,
                                  Long suggestedAssigneeId, LocalDate suggestedDueDate,
                                  LocalDateTime suggestedDatetime, List<Long> suggestedParticipantIds) {
}
