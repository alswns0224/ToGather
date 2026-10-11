package com.togather.assistant.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 내 할 일: 담당 Task, 개인 할 일, 할 일 후보
 *
 * @param assignedTasks C의 TaskService.getMyTasks 구현 후 채워진다.
 */
public record MyTodoListDTO(List<AssignedTask> assignedTasks, List<PersonalTaskResponse> personalTasks,
                            List<SuggestionDTO> taskSuggestions) {

    public record AssignedTask(Long taskId, Long workspaceId, String title, LocalDate dueDate) {
    }
}
