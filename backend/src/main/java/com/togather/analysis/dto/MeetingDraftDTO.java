package com.togather.analysis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회의 제안 → 회의 초안 (SD-08-05). 등록 요청에도 같은 형식을 쓴다.
 *
 * @param startAt 정해졌으면 그 시간으로 확정 회의, null이면 일정 조율 중 회의를 만든다
 * @param endAt   null이면 시작 후 1시간
 * @param description 화면 참고용 (회의에는 저장하지 않는다)
 */
public record MeetingDraftDTO(@NotBlank @Size(max = 200) String title, LocalDateTime startAt,
                              LocalDateTime endAt, List<Long> participantIds, @Size(max = 4000) String description) {
}
