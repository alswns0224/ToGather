package com.togather.analysis.service;

import com.togather.analysis.domain.AnalysisItem;
import com.togather.analysis.repository.AnalysisItemRepository;
import com.togather.common.enums.ItemType;
import com.togather.task.event.TaskDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 논의 정리에서 만든 팀 작업이 지워지면, 그 후속 작업 항목을 "아직 만들지 않음"으로 되돌린다.
 * 그래야 분석 화면이 지워진 작업을 "팀 작업 생성됨 · 팀 작업 보기"로 가리키지 않고, 필요하면 다시 만들 수 있다.
 */
@Component
@RequiredArgsConstructor
public class AnalysisTaskLinkCleaner {

    private final AnalysisItemRepository analysisItemRepository;

    @EventListener
    public void onTaskDeleted(TaskDeletedEvent event) {
        for (AnalysisItem item : analysisItemRepository.findAllByItemTypeAndOutputTargetId(ItemType.FOLLOW_UP,
                event.taskId())) {
            item.recordOutput(null, null);
        }
    }
}
