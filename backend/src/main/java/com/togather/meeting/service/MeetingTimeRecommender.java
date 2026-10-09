package com.togather.meeting.service;

import com.togather.common.domain.DateRange;
import com.togather.common.enums.SlotPreference;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 회의 시간 추천 (UC-10-08). 가용 시간은 30분 칸 단위로 다룬다.
 *
 * <p>규칙</p>
 * <ul>
 *   <li>후보 시작 시각은 후보 기간 안에서 30분 간격, 하루 중 {@value #DAY_START_HOUR}시 ~ {@value #DAY_END_HOUR}시 사이만 본다.</li>
 *   <li>참여자가 회의 시간의 모든 칸을 "가능" 또는 "비선호"로 표시했으면 참석 가능. 표시하지 않은 칸이나 "불가능"이 하나라도 있으면 불가능.</li>
 *   <li>가능 인원이 많은 순 → 비선호 인원이 적은 순 → 이른 시각 순으로 정렬하고, 서로 겹치지 않는 후보를 고른다.</li>
 *   <li>가용 시간에 응답하지 않은 참여자는 계산에서 뺀다.</li>
 * </ul>
 */
@Component
public class MeetingTimeRecommender {

    public static final int SLOT_MINUTES = 30;
    public static final int DAY_START_HOUR = 9;
    public static final int DAY_END_HOUR = 22;
    public static final int MAX_CANDIDATES = 5;

    /**
     * @param unavailableUserIds 이 시간에 참석할 수 없는 참여자
     */
    public record Scored(LocalDateTime startAt, int availableCount, int nonPreferredCount,
                         List<Long> unavailableUserIds) {
    }

    /**
     * @param responses 응답한 참여자별 칸 시작 시각 → 선호
     */
    public List<Scored> recommend(DateRange period, int durationMinutes,
                                  Map<Long, Map<LocalDateTime, SlotPreference>> responses) {
        List<Scored> scores = new ArrayList<>();
        for (LocalDateTime start : candidateStarts(period, durationMinutes)) {
            scores.add(score(start, durationMinutes, responses));
        }
        scores.sort(Comparator.comparingInt(Scored::availableCount).reversed()
                .thenComparingInt(Scored::nonPreferredCount)
                .thenComparing(Scored::startAt));

        List<Scored> selected = new ArrayList<>();
        for (Scored candidate : scores) {
            if (selected.size() >= MAX_CANDIDATES || candidate.availableCount() == 0) {
                break;
            }
            boolean overlaps = selected.stream().anyMatch(s -> overlaps(s.startAt(), candidate.startAt(), durationMinutes));
            if (!overlaps) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    /** 가용 시간 입력 표의 칸 시작 시각 목록 (하루 중 표시 범위 안) */
    public List<LocalDateTime> cells(DateRange period) {
        List<LocalDateTime> cells = new ArrayList<>();
        for (LocalDateTime t = roundUp(period.getStart()); !t.plusMinutes(SLOT_MINUTES).isAfter(period.getEnd());
             t = t.plusMinutes(SLOT_MINUTES)) {
            if (withinDay(t, t.plusMinutes(SLOT_MINUTES))) {
                cells.add(t);
            }
        }
        return cells;
    }

    /** 일정이 있는 시간대와 겹치는 칸 */
    public List<LocalDateTime> overlappingCells(List<LocalDateTime> cells, List<DateRange> busy) {
        return cells.stream()
                .filter(c -> busy.stream().anyMatch(b ->
                        c.isBefore(b.getEnd()) && b.getStart().isBefore(c.plusMinutes(SLOT_MINUTES))))
                .toList();
    }

    private List<LocalDateTime> candidateStarts(DateRange period, int durationMinutes) {
        List<LocalDateTime> starts = new ArrayList<>();
        for (LocalDateTime t = roundUp(period.getStart()); !t.plusMinutes(durationMinutes).isAfter(period.getEnd());
             t = t.plusMinutes(SLOT_MINUTES)) {
            if (withinDay(t, t.plusMinutes(durationMinutes))) {
                starts.add(t);
            }
        }
        return starts;
    }

    private Scored score(LocalDateTime start, int durationMinutes,
                         Map<Long, Map<LocalDateTime, SlotPreference>> responses) {
        int available = 0;
        int nonPreferred = 0;
        List<Long> unavailable = new ArrayList<>();
        for (Map.Entry<Long, Map<LocalDateTime, SlotPreference>> entry : responses.entrySet()) {
            boolean canAttend = true;
            boolean notPreferred = false;
            for (LocalDateTime cell = start; cell.isBefore(start.plusMinutes(durationMinutes));
                 cell = cell.plusMinutes(SLOT_MINUTES)) {
                SlotPreference preference = entry.getValue().get(cell);
                if (preference == null || preference == SlotPreference.UNAVAILABLE) {
                    canAttend = false;
                    break;
                }
                if (preference == SlotPreference.NOT_PREFERRED) {
                    notPreferred = true;
                }
            }
            if (canAttend) {
                available++;
                if (notPreferred) {
                    nonPreferred++;
                }
            } else {
                unavailable.add(entry.getKey());
            }
        }
        return new Scored(start, available, nonPreferred, unavailable);
    }

    private static boolean overlaps(LocalDateTime a, LocalDateTime b, int durationMinutes) {
        return a.isBefore(b.plusMinutes(durationMinutes)) && b.isBefore(a.plusMinutes(durationMinutes));
    }

    /** 같은 날, DAY_START ~ DAY_END 안에 들어가는지 */
    private static boolean withinDay(LocalDateTime start, LocalDateTime end) {
        LocalTime dayStart = LocalTime.of(DAY_START_HOUR, 0);
        LocalDateTime dayEnd = start.toLocalDate().atTime(DAY_END_HOUR, 0);
        return !start.toLocalTime().isBefore(dayStart) && !end.isAfter(dayEnd);
    }

    /** 30분 단위로 올림 */
    private static LocalDateTime roundUp(LocalDateTime time) {
        LocalDateTime truncated = time.withSecond(0).withNano(0);
        int remainder = truncated.getMinute() % SLOT_MINUTES;
        if (remainder == 0 && truncated.equals(time)) {
            return truncated;
        }
        return truncated.plusMinutes(SLOT_MINUTES - remainder);
    }
}
