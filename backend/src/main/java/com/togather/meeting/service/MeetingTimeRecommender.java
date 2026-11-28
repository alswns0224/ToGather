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
 *   <li>가능 인원이 많은 순 → 가중치 점수가 높은 순 → 이른 시각 순으로 정렬한다.</li>
 *   <li>가중치 점수(설계 TimeScore): 참석 가능한 사람마다 회의 시간을 30분 칸으로 보고 가능 칸 {@value #AVAILABLE_WEIGHT},
 *       비선호 칸 {@value #NOT_PREFERRED_WEIGHT}점의 평균을 내어 모두 더한다. 2시간 회의에서 1칸만 비선호면 0.875점,
 *       4칸 모두 비선호면 0.5점이라 비선호가 짧은 시간이 앞선다. 가능 인원을 먼저 보므로 더 많이 올 수 있는 시간이 항상 앞선다.</li>
 *   <li>후보끼리 시간이 겹쳐도 모두 보여 준다. (예: 10~14시 가능, 2시간 회의 → 10~12, 10:30~12:30, 11~13,
 *       11:30~13:30, 12~14)</li>
 *   <li>가능 인원과 가중치 점수가 같으면 공동 순위다. 다음 순위는 공동 개수만큼 건너뛴다. (예: 1, 1, 1, 4)</li>
 *   <li>가용 시간에 응답하지 않은 참여자는 계산에서 뺀다.</li>
 * </ul>
 */
@Component
public class MeetingTimeRecommender {

    public static final int SLOT_MINUTES = 30;
    public static final int DAY_START_HOUR = 9;
    public static final int DAY_END_HOUR = 22;
    /** 화면에서는 5개씩 "더 보기"로 보여 준다. */
    public static final int MAX_CANDIDATES = 30;
    static final double AVAILABLE_WEIGHT = 1.0;
    static final double NOT_PREFERRED_WEIGHT = 0.5;

    /**
     * @param rank               순위. 가능 인원·가중치 점수가 같으면 같은 순위(공동)
     * @param nonPreferredCount  비선호 칸이 하나라도 있는 참석 가능 인원 (화면 표시용)
     * @param score              가중치 점수 (참석 가능한 사람별 칸 점수 평균의 합)
     * @param unavailableUserIds 이 시간에 참석할 수 없는 참여자
     */
    public record Scored(int rank, LocalDateTime startAt, int availableCount, int nonPreferredCount, double score,
                         List<Long> unavailableUserIds) {

        Scored withRank(int rank) {
            return new Scored(rank, startAt, availableCount, nonPreferredCount, score, unavailableUserIds);
        }

        boolean sameScore(Scored other) {
            return availableCount == other.availableCount && Math.abs(score - other.score) < 1e-9;
        }
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
                .thenComparing(Comparator.comparingDouble(Scored::score).reversed())
                .thenComparing(Scored::startAt));

        List<Scored> selected = scores.stream()
                .filter(candidate -> candidate.availableCount() > 0)
                .limit(MAX_CANDIDATES)
                .toList();
        return assignRanks(selected);
    }

    /** 공동 순위: 앞 후보와 점수가 같으면 같은 순위, 다르면 (앞에 있는 후보 수 + 1)위 */
    private static List<Scored> assignRanks(List<Scored> sorted) {
        List<Scored> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Scored current = sorted.get(i);
            int rank = i > 0 && current.sameScore(sorted.get(i - 1)) ? ranked.get(i - 1).rank() : i + 1;
            ranked.add(current.withRank(rank));
        }
        return ranked;
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
        double total = 0;
        List<Long> unavailable = new ArrayList<>();
        for (Map.Entry<Long, Map<LocalDateTime, SlotPreference>> entry : responses.entrySet()) {
            boolean canAttend = true;
            int cells = 0;
            int notPreferredCells = 0;
            for (LocalDateTime cell = start; cell.isBefore(start.plusMinutes(durationMinutes));
                 cell = cell.plusMinutes(SLOT_MINUTES)) {
                SlotPreference preference = entry.getValue().get(cell);
                if (preference == null || preference == SlotPreference.UNAVAILABLE) {
                    canAttend = false;
                    break;
                }
                cells++;
                if (preference == SlotPreference.NOT_PREFERRED) {
                    notPreferredCells++;
                }
            }
            if (canAttend && cells > 0) {
                available++;
                if (notPreferredCells > 0) {
                    nonPreferred++;
                }
                total += ((cells - notPreferredCells) * AVAILABLE_WEIGHT + notPreferredCells * NOT_PREFERRED_WEIGHT) / cells;
            } else {
                unavailable.add(entry.getKey());
            }
        }
        return new Scored(0, start, available, nonPreferred, total, unavailable);
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
