package com.togather.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.togather.analysis.service.DiscussionAnalyzerTimeAccess;
import com.togather.common.enums.TaskStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** 마감 시각: 마감일과 함께만 두고, AI가 준 "HH:mm"을 읽는다. */
class TaskDueTimeTest {

    @Test
    void 마감일이_있으면_시각을_둔다() {
        Task task = new Task(1L, "발표 자료", null, LocalDate.of(2026, 11, 30), TaskStatus.IN_PROGRESS);
        task.changeDueTime(LocalTime.of(18, 0));
        assertThat(task.getDueTime()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void 마감일이_없으면_시각도_두지_않는다() {
        Task task = new Task(1L, "발표 자료", null, null, TaskStatus.IN_PROGRESS);
        task.changeDueTime(LocalTime.of(18, 0));
        assertThat(task.getDueTime()).isNull();
    }

    @Test
    void AI가_준_마감_시각을_읽는다() {
        assertThat(DiscussionAnalyzerTimeAccess.parseTime("18:00")).isEqualTo(LocalTime.of(18, 0));
        assertThat(DiscussionAnalyzerTimeAccess.parseTime("09:30:00")).isEqualTo(LocalTime.of(9, 30));
        assertThat(DiscussionAnalyzerTimeAccess.parseTime("null")).isNull();
        assertThat(DiscussionAnalyzerTimeAccess.parseTime("저녁")).isNull();
    }
}
