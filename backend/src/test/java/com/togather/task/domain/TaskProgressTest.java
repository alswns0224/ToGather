package com.togather.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.togather.common.enums.RiskLevel;
import com.togather.common.enums.TaskStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TaskProgressTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Test
    void 산출물이_없으면_Task_상태로_진행률을_계산한다() {
        Task task = new Task(1L, "발표 준비", null, null, TaskStatus.IN_PROGRESS);

        assertThat(task.calculateProgress()).isZero();
        task.changeStatus(TaskStatus.DONE);
        assertThat(task.calculateProgress()).isEqualTo(100);
    }

    @Test
    void 제출_비율을_내림하고_해제하면_진행률이_다시_줄어든다() {
        Task task = new Task(1L, "발표 준비", null, TODAY, TaskStatus.IN_PROGRESS);
        TaskDeliverable notes = task.addDeliverable("메모", "txt");
        TaskDeliverable slides = task.addDeliverable("슬라이드", "pptx");
        TaskDeliverable handout = task.addDeliverable("배포 자료", "pdf");

        assertThat(task.calculateProgress()).isZero();
        notes.submit("notes", "notes.txt", null, 1L);
        assertThat(task.calculateProgress()).isEqualTo(33);
        slides.submit("slides", "slides.pptx", null, 1L);
        handout.submit("handout", "handout.pdf", null, 1L);
        assertThat(task.calculateProgress()).isEqualTo(100);
        handout.unsubmit();
        assertThat(task.calculateProgress()).isEqualTo(66);
    }

    @ParameterizedTest
    @CsvSource({
        "-1, 100, DANGER",
        "0, 49, DANGER",
        "0, 50, WARNING",
        "2, 49, DANGER",
        "2, 50, WARNING",
        "3, 49, WARNING",
        "5, 79, WARNING",
        "5, 80, NORMAL",
        "6, 0, NORMAL"
    })
    void 마감일과_진행률_경계에서_위험도를_판정한다(
        int daysLeft, int submittedCount, RiskLevel expected
    ) {
        Task task = new Task(1L, "발표 준비", null, TODAY.plusDays(daysLeft), TaskStatus.IN_PROGRESS);
        for (int index = 0; index < 100; index++) {
            TaskDeliverable deliverable = task.addDeliverable("산출물 " + index, "pdf");
            if (index < submittedCount) {
                deliverable.submit("file-" + index, "file.pdf", null, 1L);
            }
        }

        assertThat(task.evaluateRisk(TODAY)).isEqualTo(expected);
    }

    @Test
    void 완료했거나_마감일이_없으면_위험도가_정상이다() {
        Task completed = new Task(1L, "완료 Task", null, TODAY.minusDays(1), TaskStatus.DONE);
        Task unscheduled = new Task(1L, "마감 미정 Task", null, null, TaskStatus.IN_PROGRESS);

        assertThat(completed.evaluateRisk(TODAY)).isEqualTo(RiskLevel.NORMAL);
        assertThat(unscheduled.evaluateRisk(TODAY)).isEqualTo(RiskLevel.NORMAL);
    }
}
