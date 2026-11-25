package com.togather.meeting.dto;

import java.util.List;

/**
 * 확정하려는 시간에 이미 다른 일정이 있는 참여자.
 *
 * @param reasons ToGather에서 확정된 다른 회의 제목. 회의가 아니라 본인 캘린더 일정이면 "캘린더 일정"
 */
public record ConflictResponse(Long userId, String name, List<String> reasons) {
}
