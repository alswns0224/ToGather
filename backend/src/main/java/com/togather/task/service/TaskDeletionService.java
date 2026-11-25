package com.togather.task.service;

import com.togather.common.enums.MeetingStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.meeting.dto.MeetingSummary;
import com.togather.meeting.service.MeetingService;
import com.togather.task.domain.Task;
import com.togather.task.event.TaskDeletedEvent;
import com.togather.task.repository.TaskRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Task 삭제와 회의 안건 보호. 회의 데이터는 기존 공유 Service를 통해서만 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskDeletionService {

    private final TaskRepository taskRepository;
    private final MeetingService meetingService;
    private final ApplicationEventPublisher events;

    public List<MeetingSummary> getDeletionBlockers(Long workspaceId, Long taskId) {
        taskRepository.findById(taskId)
            .filter(task -> task.getWorkspaceId().equals(workspaceId))
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        return activeMeetings(taskId);
    }

    @Transactional
    public void deleteTask(Long workspaceId, Long taskId) {
        Task task = taskRepository.findLockedByTaskIdAndWorkspaceId(taskId, workspaceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        // 화면의 사전 확인 이후 안건이 추가됐을 수 있으므로 삭제 시점에도 검사한다.
        if (!activeMeetings(taskId).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_IN_ACTIVE_MEETING);
        }
        taskRepository.delete(task);
        events.publishEvent(new TaskDeletedEvent(workspaceId, taskId));
    }

    private List<MeetingSummary> activeMeetings(Long taskId) {
        return meetingService.getMeetingsByTask(taskId).stream()
            .filter(meeting -> meeting.status() == MeetingStatus.SCHEDULING
                || meeting.status() == MeetingStatus.CONFIRMED)
            .toList();
    }
}
