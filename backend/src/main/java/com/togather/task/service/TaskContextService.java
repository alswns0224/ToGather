package com.togather.task.service;

import com.togather.analysis.dto.AnalysisItemDTO;
import com.togather.analysis.service.AnalysisService;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.meeting.dto.MeetingSummary;
import com.togather.meeting.service.MeetingService;
import com.togather.task.domain.Task;
import com.togather.task.domain.TaskContentLink;
import com.togather.task.domain.TaskDeliverable;
import com.togather.task.dto.TaskContext;
import com.togather.task.dto.TaskDeliverableSummary;
import com.togather.task.dto.TaskDetailResponse;
import com.togather.task.dto.TaskSummary;
import com.togather.task.repository.TaskContentLinkRepository;
import com.togather.task.repository.TaskDeliverableRepository;
import com.togather.task.repository.TaskRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskContextService {

    private final TaskRepository taskRepository;
    private final TaskContentLinkRepository taskContentLinkRepository;
    private final TaskDeliverableRepository taskDeliverableRepository;

    private final TaskService taskService;
    private final ContentRefService contentRefService;
    private final AnalysisService analysisService;
    private final MeetingService meetingService;

    /**
     * Task 상세와 연결된 협업 맥락을 한 번에 조회한다.
     */
    public TaskContext getTaskContext(Long taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() ->
                new BusinessException(ErrorCode.TASK_NOT_FOUND)
            );

        TaskDetailResponse taskDetail =
            TaskDetailResponse.from(task, LocalDate.now());

        List<TaskContentLink> links =
            taskContentLinkRepository.findAllByTaskId(taskId);

        List<Long> contentRefIds = links.stream()
            .map(TaskContentLink::getContentRefId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        List<ContentRefDTO> contents =
            contentRefIds.isEmpty()
                ? List.of()
                : contentRefService.getContentRefs(contentRefIds);

        Map<Provider, List<ContentRefDTO>> contentsByProvider =
            groupAndSortContents(contents);

        List<TaskDeliverableSummary> deliverables =
            task.getDeliverables().stream()
                .sorted(Comparator.comparing(
                    TaskDeliverable::getDeliverableId
                ))
                .map(TaskDeliverableSummary::from)
                .toList();

        AnalysisItemDTO originDecision = null;
        List<ContentRefDTO> originEvidence = List.of();

        if (task.getOriginAnalysisItemId() != null) {
            originDecision = analysisService.getItemSummary(
                task.getOriginAnalysisItemId()
            );

            List<Long> evidenceIds =
                originDecision.evidenceContentRefIds();

            if (evidenceIds != null && !evidenceIds.isEmpty()) {
                originEvidence =
                    contentRefService.getContentRefs(evidenceIds);
            }
        }

        List<MeetingSummary> meetings =
            meetingService.getMeetingsByTask(taskId);

        return new TaskContext(
            taskDetail,
            contentsByProvider,
            deliverables,
            originDecision,
            originEvidence,
            meetings
        );
    }

    /**
     * 여러 콘텐츠 각각에 연결된 Task 목록을 반환한다.
     *
     * 일반 관련자료 링크뿐 아니라 Drive 산출물로 제출된 Task도 포함한다.
     */
    public Map<Long, List<TaskSummary>> getTasksByContents(
        List<Long> contentRefIds
    ) {
        if (contentRefIds == null || contentRefIds.isEmpty()) {
            return Map.of();
        }

        List<Long> ids = contentRefIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        Map<Long, Set<Long>> taskIdsByContent =
            new LinkedHashMap<>();

        ids.forEach(id ->
            taskIdsByContent.put(id, new LinkedHashSet<>())
        );

        taskContentLinkRepository
            .findAllByContentRefIdIn(ids)
            .forEach(link -> {
                Set<Long> taskIds =
                    taskIdsByContent.get(link.getContentRefId());

                if (taskIds != null) {
                    taskIds.add(link.getTaskId());
                }
            });

        addDeliverableTasks(ids, taskIdsByContent);

        Set<Long> allTaskIds = taskIdsByContent.values()
            .stream()
            .flatMap(Collection::stream)
            .collect(Collectors.toCollection(
                LinkedHashSet::new
            ));

        Map<Long, TaskSummary> tasksById =
            taskService.getTasks(allTaskIds).stream()
                .collect(Collectors.toMap(
                    TaskSummary::taskId,
                    Function.identity()
                ));

        Map<Long, List<TaskSummary>> result =
            new LinkedHashMap<>();

        ids.forEach(contentRefId -> {
            List<TaskSummary> tasks =
                taskIdsByContent
                    .getOrDefault(
                        contentRefId,
                        Set.of()
                    )
                    .stream()
                    .map(tasksById::get)
                    .filter(Objects::nonNull)
                    .toList();

            result.put(contentRefId, tasks);
        });

        return result;
    }

    private void addDeliverableTasks(
        List<Long> contentRefIds,
        Map<Long, Set<Long>> taskIdsByContent
    ) {
        List<ContentRefDTO> contents =
            contentRefService.getContentRefs(contentRefIds);

        Map<String, Long> driveContentIdsByExternalId =
            contents.stream()
                .filter(content ->
                    content.provider() == Provider.GOOGLE_DRIVE
                )
                .filter(content ->
                    content.externalId() != null
                        && !content.externalId().isBlank()
                )
                .collect(Collectors.toMap(
                    ContentRefDTO::externalId,
                    ContentRefDTO::contentRefId,
                    (first, second) -> first
                ));

        if (driveContentIdsByExternalId.isEmpty()) {
            return;
        }

        taskDeliverableRepository
            .findAllByFileIdIn(
                driveContentIdsByExternalId.keySet()
            )
            .forEach(deliverable -> {
                Long contentRefId =
                    driveContentIdsByExternalId.get(
                        deliverable.getFileId()
                    );

                if (contentRefId != null) {
                    taskIdsByContent
                        .get(contentRefId)
                        .add(
                            deliverable
                                .getTask()
                                .getTaskId()
                        );
                }
            });
    }

    private Map<Provider, List<ContentRefDTO>>
    groupAndSortContents(
        List<ContentRefDTO> contents
    ) {
        Comparator<ContentRefDTO> comparator =
            Comparator.comparing(
                ContentRefDTO::lastModifiedAt,
                Comparator.nullsLast(
                    Comparator.reverseOrder()
                )
            );

        return contents.stream()
            .sorted(comparator)
            .collect(Collectors.groupingBy(
                ContentRefDTO::provider,
                LinkedHashMap::new,
                Collectors.toList()
            ));
    }
}
