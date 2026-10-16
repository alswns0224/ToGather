package com.togather.analysis.dto;

import com.togather.common.enums.ItemType;
import java.util.List;

/** 결정사항 요약 (docs/shared-interfaces.md [결정 8]). C가 Task 협업 맥락에서 근거 결정사항을 표시할 때 쓴다. */
public record AnalysisItemDTO(Long itemId, ItemType itemType, String content, List<Long> evidenceContentRefIds) {
}
