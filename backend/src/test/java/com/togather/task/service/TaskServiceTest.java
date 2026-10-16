package com.togather.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.TaskStatus;
import com.togather.common.exception.BusinessException;
import com.togather.task.domain.Task;
import com.togather.task.dto.TaskCommand;
import com.togather.task.dto.TaskSummary;
import com.togather.task.repository.TaskRepository;
import com.togather.workspace.service.WorkspaceMemberService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private WorkspaceMemberService workspaceMemberService;

    @InjectMocks
    private TaskService taskService;

    @Test
    void createTask_savesTaskAndReturnsId() {
        // given
        Long workspaceId = 1L;

        TaskCommand command = new TaskCommand(
            "발표 자료 준비",
            "중간 발표 자료 작성",
            null,
            LocalDate.of(2026, 10, 30),
            TaskStatus.IN_PROGRESS,
            List.of(),
            null
        );

        when(taskRepository.save(any(Task.class)))
            .thenAnswer(invocation -> {
                Task task = invocation.getArgument(0);
                ReflectionTestUtils.setField(task, "taskId", 100L);
                return task;
            });

        // when
        Long taskId = taskService.createTask(
            workspaceId,
            command
        );

        // then
        assertThat(taskId).isEqualTo(100L);

        ArgumentCaptor<Task> captor =
            ArgumentCaptor.forClass(Task.class);

        verify(taskRepository).save(captor.capture());

        Task savedTask = captor.getValue();

        assertThat(savedTask.getWorkspaceId())
            .isEqualTo(workspaceId);
        assertThat(savedTask.getTitle())
            .isEqualTo("발표 자료 준비");
        assertThat(savedTask.getDescription())
            .isEqualTo("중간 발표 자료 작성");
        assertThat(savedTask.getDueDate())
            .isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(savedTask.getStatus())
            .isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void updateTask_updatesProvidedFieldsAndKeepsNullFields() {
        // given
        Long workspaceId = 1L;
        Long taskId = 100L;

        LocalDate originalDueDate =
            LocalDate.of(2026, 10, 30);

        Task task = new Task(
            workspaceId,
            "기존 제목",
            "기존 설명",
            originalDueDate,
            TaskStatus.IN_PROGRESS
        );

        task.assignTo(10L);

        when(taskRepository.findById(taskId))
            .thenReturn(Optional.of(task));

        // when
        TaskSummary result = taskService.updateTask(
            workspaceId,
            taskId,
            "수정된 제목",
            null,
            null,
            null,
            TaskStatus.DONE
        );

        // then
        assertThat(task.getTitle())
            .isEqualTo("수정된 제목");

        // PATCH에서 보내지 않은 값은 기존 값 유지
        assertThat(task.getDescription())
            .isEqualTo("기존 설명");
        assertThat(task.getAssigneeId())
            .isEqualTo(10L);
        assertThat(task.getDueDate())
            .isEqualTo(originalDueDate);

        assertThat(task.getStatus())
            .isEqualTo(TaskStatus.DONE);

        assertThat(result.title())
            .isEqualTo("수정된 제목");
    }

    @Test
    void deleteTask_deletesTask() {
        // given
        Long workspaceId = 1L;
        Long taskId = 100L;

        Task task = new Task(
            workspaceId,
            "삭제할 Task",
            "설명",
            LocalDate.of(2026, 10, 30),
            TaskStatus.IN_PROGRESS
        );

        when(taskRepository.findById(taskId))
            .thenReturn(Optional.of(task));

        // when
        taskService.deleteTask(
            workspaceId,
            taskId
        );

        // then
        verify(taskRepository).delete(task);
    }

    @Test
    void updateTask_rejectsTaskFromAnotherWorkspace() {
        // given
        Long requestedWorkspaceId = 1L;
        Long actualWorkspaceId = 2L;
        Long taskId = 100L;

        Task task = new Task(
            actualWorkspaceId,
            "다른 워크스페이스 Task",
            "설명",
            LocalDate.of(2026, 10, 30),
            TaskStatus.IN_PROGRESS
        );

        when(taskRepository.findById(taskId))
            .thenReturn(Optional.of(task));

        // when & then
        assertThatThrownBy(() ->
            taskService.updateTask(
                requestedWorkspaceId,
                taskId,
                "수정 시도",
                null,
                null,
                null,
                null
            )
        ).isInstanceOf(BusinessException.class);

        assertThat(task.getTitle())
            .isEqualTo("다른 워크스페이스 Task");
    }

    @Test
    void deleteTask_rejectsTaskFromAnotherWorkspace() {
        // given
        Long requestedWorkspaceId = 1L;
        Long actualWorkspaceId = 2L;
        Long taskId = 100L;

        Task task = new Task(
            actualWorkspaceId,
            "다른 워크스페이스 Task",
            "설명",
            LocalDate.of(2026, 10, 30),
            TaskStatus.IN_PROGRESS
        );

        when(taskRepository.findById(taskId))
            .thenReturn(Optional.of(task));

        // when & then
        assertThatThrownBy(() ->
            taskService.deleteTask(
                requestedWorkspaceId,
                taskId
            )
        ).isInstanceOf(BusinessException.class);

        verify(taskRepository, never())
            .delete(any(Task.class));
    }
}
