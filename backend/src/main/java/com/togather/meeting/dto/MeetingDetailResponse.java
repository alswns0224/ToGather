package com.togather.meeting.dto;

import com.togather.common.enums.MeetingStatus;
import com.togather.task.dto.TaskSummary;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회의 상세 (UI-10)
 *
 * @param canManage 현재 사용자가 수정·취소·확정할 수 있는지 (만든 사람 또는 팀장)
 */
public record MeetingDetailResponse(
        Long meetingId,
        String title,
        MeetingStatus status,
        int durationMinutes,
        LocalDateTime candidateStartAt,
        LocalDateTime candidateEndAt,
        String meetingMode,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Long calendarRefId,
        String meetLink,
        boolean calendarSyncFailed,
        Long createdBy,
        boolean canManage,
        boolean participating,
        List<ParticipantResponse> participants,
        List<AgendaResponse> agendas
) {

    public record ParticipantResponse(Long userId, String name, String profileImage, boolean hasResponded) {
    }

    /** 직접 입력 안건은 taskId/task가 null이고 title이 있다. 기존 Task 안건의 title은 null이다. */
    public record AgendaResponse(Long agendaId, int orderNo, Long taskId, TaskSummary task, String title) {
    }
}
