package com.togather.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회의 제안 → 회의 초안 (SD-08-05). 등록 요청에도 같은 형식을 쓴다.
 *
 * <p>회의는 항상 "일정 조율 중"으로 만든다. 참여자가 후보 기간 안에서 가능한 시간을 입력(투표)하고,
 * 추천 시간으로 확정한다. (일반 회의 만들기와 같다)
 *
 * @param startAt 대화에서 정한 시작 일시 (참고용, 후보 기간 기본값을 정할 때 쓴다)
 * @param endAt   대화에서 정한 끝 (참고용)
 * @param description 화면 참고용 (회의에는 저장하지 않는다)
 * @param durationMinutes 회의 길이(분). 있으면 endAt보다 먼저 쓴다. 대화에 나온 길이가 기본값
 * @param candidateStartAt 시간 투표를 받을 후보 기간 시작. null이면 기본 기간
 * @param candidateEndAt   후보 기간 끝
 */
public record MeetingDraftDTO(@NotBlank @Size(max = 200) String title, LocalDateTime startAt,
                              LocalDateTime endAt, List<Long> participantIds, @Size(max = 4000) String description,
                              Integer durationMinutes, LocalDateTime candidateStartAt,
                              LocalDateTime candidateEndAt) {

    public MeetingDraftDTO(String title, LocalDateTime startAt, LocalDateTime endAt, List<Long> participantIds,
                           String description) {
        this(title, startAt, endAt, participantIds, description, null, null, null);
    }

    public MeetingDraftDTO(String title, LocalDateTime startAt, LocalDateTime endAt, List<Long> participantIds,
                           String description, Integer durationMinutes) {
        this(title, startAt, endAt, participantIds, description, durationMinutes, null, null);
    }
}
