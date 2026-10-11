package com.togather.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 항목 수정: 내용과 담당자·마감일(후속 작업), 일시·참여자(회의 제안) */
public record AnalysisItemUpdateRequest(@NotBlank @Size(max = 2000) String content, Long suggestedAssigneeId,
                                        LocalDate suggestedDueDate, LocalDateTime suggestedDatetime,
                                        List<Long> suggestedParticipantIds) {
}
