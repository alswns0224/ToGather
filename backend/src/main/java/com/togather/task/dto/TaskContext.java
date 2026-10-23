package com.togather.task.dto;

import com.togather.analysis.dto.AnalysisItemDTO;
import com.togather.common.enums.Provider;
import com.togather.content.dto.ContentRefDTO;
import com.togather.meeting.dto.MeetingSummary;
import java.util.List;
import java.util.Map;

public record TaskContext(
    TaskDetailResponse task,
    Map<Provider, List<ContentRefDTO>> contentsByProvider,
    List<TaskDeliverableSummary> deliverables,
    AnalysisItemDTO originDecision,
    List<ContentRefDTO> originEvidence,
    List<MeetingSummary> meetings
) {
}
