package com.togather.analysis.dto;

import com.togather.common.enums.ItemType;
import com.togather.common.enums.OutputStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * UI-08 분석 항목
 *
 * @param undetermined   담당자·마감일(후속 작업)이나 일시(회의 제안)를 대화에서 찾지 못한 경우
 * @param outputTargetId 연계 결과: 후속 작업이면 Task ID, 회의 제안이면 Calendar 일정(ContentRef) ID
 */
public record AnalysisItemResponse(Long itemId, ItemType itemType, String content, boolean undetermined,
                                   Long suggestedAssigneeId, String suggestedAssigneeName, LocalDate suggestedDueDate,
                                   LocalDateTime suggestedDatetime, List<Long> suggestedParticipantIds,
                                   OutputStatus outputStatus, Long outputTargetId,
                                   List<AnalysisEvidenceDTO> evidences) {
}
