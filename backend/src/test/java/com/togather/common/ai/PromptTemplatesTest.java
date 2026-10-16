package com.togather.common.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PromptTemplatesTest {

    @Test
    void 오늘_날짜에_요일을_붙인다() {
        assertThat(PromptTemplates.today(LocalDate.of(2026, 10, 16))).isEqualTo("2026-10-16 (금요일)");
    }

    @Test
    void 날짜표는_한_주씩_줄을_바꾼다() {
        String calendar = PromptTemplates.calendar(LocalDate.of(2026, 10, 16), 8);

        assertThat(calendar).startsWith("2026-10-16(금), 2026-10-17(토)");
        assertThat(calendar).contains("2026-10-20(화)");
        assertThat(calendar.split("\n")).hasSize(2);
    }

    @Test
    void 프롬프트_변수를_채운다() {
        String prompt = new PromptTemplates().render("discussion-analysis", java.util.Map.of(
                "today", "2026-10-16 (금요일)", "calendar", "표", "channel", "general", "previousItems", "(없음)",
                "messages", "m1"));

        assertThat(prompt).contains("2026-10-16 (금요일)").contains("## 날짜표").doesNotContain("{{");
    }
}
