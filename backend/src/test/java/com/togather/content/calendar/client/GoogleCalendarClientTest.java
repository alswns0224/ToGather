package com.togather.content.calendar.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class GoogleCalendarClientTest {

    @Test
    void 초가_0이어도_초까지_있는_UTC_RFC3339로_보낸다() {
        // 서울 9/27 00:00 = UTC 9/26 15:00. 쿼리에서 공백으로 바뀌는 '+'가 없어야 한다.
        assertThat(GoogleCalendarClient.rfc3339(LocalDateTime.of(2026, 9, 27, 0, 0)))
                .isEqualTo("2026-09-26T15:00:00Z");
    }
}
