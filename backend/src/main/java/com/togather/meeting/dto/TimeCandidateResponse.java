package com.togather.meeting.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 추천 회의 시간 후보 (UI-10: 순위, 가능 인원, 비선호 인원)
 *
 * @param rank             순위. 점수가 같으면 같은 순위
 * @param tied             같은 순위의 후보가 더 있으면 true (화면에 "공동 N위"로 표시)
 * @param unavailableNames 이 시간에 참석할 수 없는 참여자 이름
 */
public record TimeCandidateResponse(
        Long candidateId,
        int rank,
        boolean tied,
        LocalDateTime startAt,
        LocalDateTime endAt,
        int availableCount,
        int nonPreferredCount,
        int respondedCount,
        int participantCount,
        List<String> unavailableNames
) {
}
