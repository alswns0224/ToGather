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
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentState;
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
import java.util.stream.Collectors;
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

        when(contentRefService.getTeamContentStates(3L, Set.of(100L, 101L, 102L)))
            .thenReturn(contextStates(List.of(
                contextContent(100L, 3L, null, LocalDateTime.of(2026, 11, 1, 10, 0)),
                contextContent(101L, 3L, null, LocalDateTime.of(2026, 11, 2, 10, 0)),
                contextContent(102L, 3L, null, LocalDateTime.of(2026, 11, 3, 10, 0))
            )));

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
    void getTaskContext_sortsByCreationTimeAndPreservesDeletedSnapshot() throws Exception {
        Task task = new Task(3L, "맥락 확인", null, null, TaskStatus.IN_PROGRESS);
        setId(task, "taskId", 10L);
        task.linkOriginAnalysisItem(30L);
        ContentRef older = contextContent(100L, 3L, null, LocalDateTime.of(2026, 11, 1, 10, 0));
        ContentRef newer = contextContent(101L, 3L, null, LocalDateTime.of(2026, 11, 2, 10, 0));
        ContentRef unknown = contextContent(102L, 3L, null, null);
        older.markOriginDeleted();
        // 최근 수정된 오래된 자료도 원본 생성 시각을 기준으로 정렬한다.
        setId(older, "lastModifiedAt", LocalDateTime.of(2026, 11, 10, 10, 0));
        List<ContentRef> refs = List.of(unknown, newer, older);

        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(taskContentLinkRepository.findAllByTaskId(10L)).thenReturn(refs.stream()
            .map(ref -> new TaskContentLink(10L, ref.getContentRefId(), LinkType.REFERENCE)).toList());
        when(contentRefService.getContentRefs(List.of(102L, 101L, 100L)))
            .thenReturn(refs.stream().map(ContentRefDTO::from).toList());
        when(contentRefService.getTeamContentStates(3L, Set.of(100L, 101L, 102L)))
            .thenReturn(contextStates(refs));
        when(analysisService.getItemSummary(30L))
            .thenReturn(new AnalysisItemDTO(30L, ItemType.DECISION, "자료를 정리한다", List.of(101L, 100L)));
        when(contentRefService.getContentRefs(List.of(101L, 100L)))
            .thenReturn(List.of(ContentRefDTO.from(newer), ContentRefDTO.from(older)));

        TaskContext result = service.getTaskContext(10L);

        assertThat(result.contentsByProvider().get(Provider.SLACK))
            .extracting(ContentRefDTO::contentRefId).containsExactly(100L, 101L, 102L);
        assertThat(result.originEvidence()).extracting(ContentRefDTO::contentRefId)
            .containsExactly(100L, 101L);
        assertThat(result.contentsByProvider().get(Provider.SLACK).getFirst().content())
            .isEqualTo(older.getSnapshot());
        assertThat(result.contentStates().get(100L).originDeleted()).isTrue();
        assertThat(result.contentStates().get(102L).createdAt()).isNull();
    }

    @Test
    void getTaskContext_excludesPrivateAndOtherWorkspaceContents() throws Exception {
        Task task = new Task(3L, "팀 자료만 표시", null, null, TaskStatus.IN_PROGRESS);
        setId(task, "taskId", 10L);
        task.linkOriginAnalysisItem(30L);
        List<ContentRef> refs = List.of(
            contextContent(100L, 3L, null, null),
            contextContent(101L, 4L, null, null),
            contextContent(102L, 3L, 9L, null)
        );
        List<ContentRefDTO> contents = refs.stream().map(ContentRefDTO::from).toList();
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(taskContentLinkRepository.findAllByTaskId(10L)).thenReturn(refs.stream()
            .map(ref -> new TaskContentLink(10L, ref.getContentRefId(), LinkType.REFERENCE)).toList());
        when(contentRefService.getContentRefs(List.of(100L, 101L, 102L))).thenReturn(contents);
        when(contentRefService.getTeamContentStates(3L, Set.of(100L, 101L, 102L)))
            .thenReturn(contextStates(List.of(refs.getFirst())));
        when(analysisService.getItemSummary(30L))
            .thenReturn(new AnalysisItemDTO(30L, ItemType.DECISION, "근거 확인", List.of(100L, 101L, 102L)));

        TaskContext result = service.getTaskContext(10L);

        assertThat(result.contentsByProvider().get(Provider.SLACK))
            .extracting(ContentRefDTO::contentRefId).containsExactly(100L);
        assertThat(result.originEvidence()).extracting(ContentRefDTO::contentRefId).containsExactly(100L);
        assertThat(result.contentStates()).containsOnlyKeys(100L);
    }

    @Test
    void getTaskContext_distinguishesMovedDriveFilesFromTrash() throws Exception {
        Task task = new Task(3L, "Drive 상태 확인", null, null, TaskStatus.IN_PROGRESS);
        setId(task, "taskId", 10L);
        ContentRef moved = contextContent(100L, 3L, null, null);
        ContentRef trashed = contextContent(101L, 3L, null, null);
        setId(moved, "provider", Provider.GOOGLE_DRIVE);
        setId(trashed, "provider", Provider.GOOGLE_DRIVE);
        moved.applyDriveLocation(null, ContentRef.DriveFileState.UNAVAILABLE);
        trashed.applyDriveLocation("test-folder", ContentRef.DriveFileState.TRASHED);
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(taskContentLinkRepository.findAllByTaskId(10L)).thenReturn(List.of(
            new TaskContentLink(10L, 100L, LinkType.REFERENCE),
            new TaskContentLink(10L, 101L, LinkType.REFERENCE)
        ));
        when(contentRefService.getContentRefs(List.of(100L, 101L)))
            .thenReturn(List.of(ContentRefDTO.from(moved), ContentRefDTO.from(trashed)));
        when(contentRefService.getTeamContentStates(3L, Set.of(100L, 101L)))
            .thenReturn(contextStates(List.of(moved, trashed)));

        TaskContext result = service.getTaskContext(10L);

        assertThat(result.contentStates().get(100L).originDeleted()).isTrue();
        assertThat(result.contentStates().get(100L).driveTrashed()).isFalse();
        assertThat(result.contentStates().get(101L).originDeleted()).isTrue();
        assertThat(result.contentStates().get(101L).driveTrashed()).isTrue();
    }

    private static Map<Long, ContentState> contextStates(List<ContentRef> contents) {
        return contents.stream().collect(Collectors.toMap(
            ContentRef::getContentRefId,
            ref -> new ContentState(ref.getCreatedAt(), ref.isOriginDeleted(), ref.isDriveTrashed())
        ));
    }

    private static ContentRef contextContent(Long id, Long workspaceId, Long ownerUserId,
                                             LocalDateTime createdAt) throws Exception {
        ContentRef content = ContentRef.builder()
            .workspaceId(workspaceId).ownerUserId(ownerUserId)
            .provider(Provider.SLACK).contentType(ContentType.MESSAGE)
            .originId("context-" + id).content("보관할 내용 " + id)
            .createdAt(createdAt).lastModifiedAt(createdAt).build();
        setId(content, "contentRefId", id);
        return content;
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
