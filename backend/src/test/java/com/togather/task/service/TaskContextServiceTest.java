package com.togather.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.togather.analysis.dto.AnalysisItemDTO;
import com.togather.analysis.service.AnalysisService;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.LinkType;
import com.togather.common.enums.Provider;
import com.togather.common.enums.TaskStatus;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.meeting.dto.MeetingSummary;
import com.togather.meeting.service.MeetingService;
import com.togather.task.domain.Task;
import com.togather.task.domain.TaskContentLink;
import com.togather.task.dto.TaskContext;
import com.togather.task.dto.TaskSummary;
import com.togather.task.repository.TaskContentLinkRepository;
import com.togather.task.repository.TaskDeliverableRepository;
import com.togather.task.repository.TaskRepository;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskContextServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskContentLinkRepository taskContentLinkRepository;

    @Mock
    private TaskDeliverableRepository taskDeliverableRepository;

    @Mock
    private TaskService taskService;

    @Mock
    private ContentRefService contentRefService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private MeetingService meetingService;

    private TaskContextService service;

    @BeforeEach
    void setUp() {
        service = new TaskContextService(
            taskRepository,
            taskContentLinkRepository,
            taskDeliverableRepository,
            taskService,
            contentRefService,
            analysisService,
            meetingService
        );
    }

    @Test
    void getTaskContext_collectsContentsDecisionEvidenceAndMeetings()
        throws Exception {

        Task task = new Task(
            3L,
            "발표자료 정리",
            "중간발표 자료 준비",
            LocalDate.now().plusDays(3),
            TaskStatus.IN_PROGRESS
        );

        setId(task, "taskId", 10L);
        task.linkOriginAnalysisItem(30L);

        TaskContentLink slackLink =
            new TaskContentLink(
                10L,
                100L,
                LinkType.REFERENCE
            );

        TaskContentLink notionLink =
            new TaskContentLink(
                10L,
                101L,
                LinkType.REFERENCE
            );

        ContentRefDTO slack = new ContentRefDTO(
            100L,
            Provider.SLACK,
            ContentType.MESSAGE,
            "slack-1",
            "Slack 논의",
            "내용",
            "https://example.com/slack",
            null,
            LocalDateTime.now().minusMinutes(10),
            0,
            false
        );

        ContentRefDTO notion = new ContentRefDTO(
            101L,
            Provider.NOTION,
            ContentType.PAGE,
            "notion-1",
            "Notion 문서",
            "내용",
            "https://example.com/notion",
            null,
            LocalDateTime.now(),
            0,
            false
        );

        AnalysisItemDTO decision =
            new AnalysisItemDTO(
                30L,
                ItemType.DECISION,
                "발표자료를 먼저 완성한다.",
                List.of(102L)
            );

        ContentRefDTO evidence = new ContentRefDTO(
            102L,
            Provider.SLACK,
            ContentType.MESSAGE,
            "slack-2",
            null,
            "발표자료 먼저 하죠.",
            "https://example.com/evidence",
            null,
            LocalDateTime.now(),
            0,
            false
        );

        MeetingSummary meeting =
            new MeetingSummary(
                20L,
                "중간발표 회의",
                com.togather.common.enums.MeetingStatus.SCHEDULING,
                null
            );

        when(taskRepository.findById(10L))
            .thenReturn(Optional.of(task));

        when(taskContentLinkRepository.findAllByTaskId(10L))
            .thenReturn(List.of(slackLink, notionLink));

        when(contentRefService.getContentRefs(
            List.of(100L, 101L)
        )).thenReturn(List.of(slack, notion));

        when(analysisService.getItemSummary(30L))
            .thenReturn(decision);

        when(contentRefService.getContentRefs(
            List.of(102L)
        )).thenReturn(List.of(evidence));

        when(meetingService.getMeetingsByTask(10L))
            .thenReturn(List.of(meeting));

        TaskContext result =
            service.getTaskContext(10L);

        assertThat(result.task().taskId()).isEqualTo(10L);

        assertThat(
            result.contentsByProvider().get(Provider.SLACK)
        ).containsExactly(slack);

        assertThat(
            result.contentsByProvider().get(Provider.NOTION)
        ).containsExactly(notion);

        assertThat(result.originDecision())
            .isEqualTo(decision);

        assertThat(result.originEvidence())
            .containsExactly(evidence);

        assertThat(result.meetings())
            .containsExactly(meeting);
    }

    @Test
    void getTasksByContents_returnsLinkedTasks() {

        TaskContentLink link =
            new TaskContentLink(
                10L,
                100L,
                LinkType.REFERENCE
            );

        TaskSummary task = new TaskSummary(
            10L,
            3L,
            "발표자료 정리",
            1L,
            LocalDate.now().plusDays(3),
            TaskStatus.IN_PROGRESS,
            0,
            com.togather.common.enums.RiskLevel.NORMAL
        );

        when(
            taskContentLinkRepository
                .findAllByContentRefIdIn(
                    List.of(100L)
                )
        ).thenReturn(List.of(link));

        when(
            contentRefService
                .getContentRefs(List.of(100L))
        ).thenReturn(List.of(
            new ContentRefDTO(
                100L,
                Provider.SLACK,
                ContentType.MESSAGE,
                "slack-1",
                "논의",
                "내용",
                null,
                null,
                LocalDateTime.now(),
                0,
                false
            )
        ));

        when(taskService.getTasks(Set.of(10L)))
            .thenReturn(List.of(task));

        Map<Long, List<TaskSummary>> result =
            service.getTasksByContents(
                List.of(100L)
            );

        assertThat(result.get(100L))
            .containsExactly(task);
    }

    private static void setId(
        Object target,
        String fieldName,
        Object value
    ) throws Exception {

        Field field =
            target.getClass()
                .getDeclaredField(fieldName);

        field.setAccessible(true);
        field.set(target, value);
    }
}
