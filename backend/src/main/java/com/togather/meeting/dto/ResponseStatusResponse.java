package com.togather.meeting.dto;

import com.togather.meeting.dto.MeetingDetailResponse.ParticipantResponse;
import java.util.List;

/** CD-DTO ResponseStatusDTO. 참여자별 가용 시간 응답 현황 */
public record ResponseStatusResponse(List<ParticipantResponse> responded, List<ParticipantResponse> notResponded) {
}
