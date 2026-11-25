package com.togather.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.togather.analysis.domain.AnalysisItem;
import com.togather.analysis.repository.AnalysisItemRepository;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.OutputStatus;
import com.togather.task.event.TaskDeletedEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisTaskLinkCleanerTest {

    @Test
    void 팀_작업이_지워지면_그_작업을_가리키던_후속_작업_항목을_되돌린다() {
        AnalysisItemRepository repository = mock(AnalysisItemRepository.class);
        AnalysisItem item = new AnalysisItem(1L, ItemType.FOLLOW_UP, "발표 자료 정리", false);
        item.recordOutput(7L, OutputStatus.SUCCESS);
        when(repository.findAllByItemTypeAndOutputTargetId(ItemType.FOLLOW_UP, 7L)).thenReturn(List.of(item));

        new AnalysisTaskLinkCleaner(repository).onTaskDeleted(new TaskDeletedEvent(3L, 7L));

        assertThat(item.getOutputTargetId()).isNull();
        assertThat(item.getOutputStatus()).isNull();
    }
}
