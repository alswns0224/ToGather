package com.togather.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.togather.common.domain.DateRange;
import com.togather.common.enums.SlotPreference;
import com.togather.meeting.service.MeetingTimeRecommender.Scored;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MeetingTimeRecommenderTest {

    private final MeetingTimeRecommender recommender = new MeetingTimeRecommender();
    private final LocalDateTime day = LocalDateTime.of(2026, 10, 12, 0, 0);

    @Test
    void 모두_가능한_시간이_가장_먼저_추천된다() {
        Map<Long, Map<LocalDateTime, SlotPreference>> responses = new HashMap<>();
        responses.put(1L, cells(SlotPreference.AVAILABLE, at(10, 0), at(10, 30), at(14, 0), at(14, 30)));
        responses.put(2L, cells(SlotPreference.AVAILABLE, at(14, 0), at(14, 30)));

        List<Scored> result = recommender.recommend(new DateRange(at(9, 0), at(18, 0)), 60, responses);

        assertThat(result.get(0).startAt()).isEqualTo(at(14, 0));
        assertThat(result.get(0).availableCount()).isEqualTo(2);
        assertThat(result.get(1).startAt()).isEqualTo(at(10, 0));
        assertThat(result.get(1).unavailableUserIds()).containsExactly(2L);
    }

    @Test
    void 가능_인원이_같으면_비선호가_적은_시간이_앞선다() {
        Map<Long, Map<LocalDateTime, SlotPreference>> responses = new HashMap<>();
        Map<LocalDateTime, SlotPreference> user1 = cells(SlotPreference.AVAILABLE, at(10, 0), at(15, 0));
        user1.put(at(10, 0), SlotPreference.NOT_PREFERRED);
        responses.put(1L, user1);

        List<Scored> result = recommender.recommend(new DateRange(at(9, 0), at(18, 0)), 30, responses);

        assertThat(result).extracting(Scored::startAt).containsExactly(at(15, 0), at(10, 0));
        assertThat(result.get(1).nonPreferredCount()).isEqualTo(1);
    }

    @Test
    void 불가능_칸이_하나라도_있으면_참석할_수_없다() {
        Map<Long, Map<LocalDateTime, SlotPreference>> responses = new HashMap<>();
        Map<LocalDateTime, SlotPreference> user1 = cells(SlotPreference.AVAILABLE, at(10, 0));
        user1.put(at(10, 30), SlotPreference.UNAVAILABLE);
        responses.put(1L, user1);

        List<Scored> result = recommender.recommend(new DateRange(at(9, 0), at(18, 0)), 60, responses);

        assertThat(result).isEmpty();
    }

    @Test
    void 추천_후보끼리는_시간이_겹치지_않는다() {
        Map<Long, Map<LocalDateTime, SlotPreference>> responses = new HashMap<>();
        responses.put(1L, cells(SlotPreference.AVAILABLE, at(10, 0), at(10, 30), at(11, 0), at(11, 30)));

        List<Scored> result = recommender.recommend(new DateRange(at(9, 0), at(18, 0)), 60, responses);

        assertThat(result).extracting(Scored::startAt).containsExactly(at(10, 0), at(11, 0));
    }

    @Test
    void 하루_표시_범위_밖의_시간은_후보가_아니다() {
        List<LocalDateTime> cells = recommender.cells(new DateRange(at(7, 0), at(23, 0)));

        assertThat(cells.get(0)).isEqualTo(at(9, 0));
        assertThat(cells.get(cells.size() - 1)).isEqualTo(at(21, 30));
    }

    @Test
    void 캘린더_일정과_겹치는_칸을_찾는다() {
        List<LocalDateTime> cells = recommender.cells(new DateRange(at(9, 0), at(12, 0)));

        List<LocalDateTime> busy = recommender.overlappingCells(cells, List.of(new DateRange(at(10, 15), at(11, 0))));

        assertThat(busy).containsExactly(at(10, 0), at(10, 30));
    }

    private LocalDateTime at(int hour, int minute) {
        return day.withHour(hour).withMinute(minute);
    }

    private static Map<LocalDateTime, SlotPreference> cells(SlotPreference preference, LocalDateTime... times) {
        Map<LocalDateTime, SlotPreference> map = new HashMap<>();
        for (LocalDateTime t : times) {
            map.put(t, preference);
        }
        return map;
    }
}
