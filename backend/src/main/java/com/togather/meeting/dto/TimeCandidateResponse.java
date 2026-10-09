package com.togather.meeting.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 추천 회의 시간 후보 (UI-10: 순위, 가능 인원, 비선호 인원)
 *
 * @param unavailableNames 이 시간에 참석할 수 없는 참여자 이름
 */
public record TimeCandidateResponse(
        Long candidateId,
        int rank,
        LocalDateTime startAt,
        LocalDateTime endAt,
        int availableCount,
        int nonPreferredCount,
        int respondedCount,
        int participantCount,
        List<String> unavailableNames
) {
}
