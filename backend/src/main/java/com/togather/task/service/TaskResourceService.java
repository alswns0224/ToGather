package com.togather.task.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.LinkType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.service.DriveContentService;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.service.NotionContentService;
import com.togather.content.service.ContentRefService;
import com.togather.content.slack.service.SlackContentService;
import com.togather.task.domain.Task;
import com.togather.task.domain.TaskContentLink;
import com.togather.task.domain.TaskDeliverable;
import com.togather.task.dto.TaskReferenceCandidate;
import com.togather.task.repository.TaskContentLinkRepository;
import com.togather.task.repository.TaskRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskResourceService {

    private static final int MAX_CANDIDATES = 50;

    private final TaskRepository taskRepository;
    private final TaskContentLinkRepository linkRepository;
    private final WorkspaceAccessChecker accessChecker;
    private final ContentRefService contentRefService;
    private final SlackContentService slackContentService;
    private final NotionContentService notionContentService;
    private final DriveContentService driveContentService;

    public List<TaskReferenceCandidate> getCandidates(Long workspaceId, Long userId, Long taskId,
                                                      Provider provider, String query) {
        accessChecker.checkMember(workspaceId, userId);
        taskRepository.findById(taskId)
            .filter(task -> workspaceId.equals(task.getWorkspaceId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));

        Set<Long> linkedIds = linkRepository.findAllByTaskId(taskId).stream()
            .map(TaskContentLink::getContentRefId)
            .collect(Collectors.toSet());
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return candidates(workspaceId, userId, provider).stream()
            .filter(ref -> !linkedIds.contains(ref.contentRefId()))
            .filter(ref -> contains(ref.title(), keyword) || contains(ref.content(), keyword))
            .limit(MAX_CANDIDATES)
            .map(TaskReferenceCandidate::from)
            .toList();
    }

    @Transactional
    public void link(Long workspaceId, Long userId, Long taskId, Long contentRefId) {
        lockTask(workspaceId, userId, taskId);
        if (linkRepository.existsByTaskIdAndContentRefId(taskId, contentRefId)) {
            return;
        }
        ContentRefDTO content = requireTeamContent(workspaceId, contentRefId);
        if (content.provider() == Provider.GOOGLE_DRIVE) {
            driveContentService.getFile(workspaceId, userId, contentRefId);
        } else if (candidates(workspaceId, userId, content.provider()).stream()
            .noneMatch(ref -> contentRefId.equals(ref.contentRefId()))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        linkRepository.save(new TaskContentLink(taskId, contentRefId, LinkType.REFERENCE));
    }

    @Transactional
    public void unlink(Long workspaceId, Long userId, Long taskId, Long contentRefId) {
        lockTask(workspaceId, userId, taskId);
        linkRepository.deleteByTaskIdAndContentRefId(taskId, contentRefId);
    }

    @Transactional
    public void submit(Long workspaceId, Long userId, Long taskId, Long deliverableId, Long contentRefId) {
        Task task = lockTask(workspaceId, userId, taskId);
        TaskDeliverable deliverable = findDeliverable(task, deliverableId);
        ContentRefDTO file = driveContentService.getFile(workspaceId, userId, contentRefId);
        if (!deliverable.acceptsExtension(file.title())) {
            throw new BusinessException(ErrorCode.DELIVERABLE_EXTENSION_MISMATCH);
        }
        if (deliverable.isSubmitted()) {
            if (file.externalId().equals(deliverable.getFileId())) {
                return;
            }
            throw new BusinessException(ErrorCode.INVALID_INPUT, "기존 제출을 해제한 뒤 다시 제출해 주세요.");
        }
        deliverable.submit(file.externalId(), file.title(), file.deepLink(), userId);
        task.refreshStatusByDeliverables();
    }

    @Transactional
    public void unsubmit(Long workspaceId, Long userId, Long taskId, Long deliverableId) {
        Task task = lockTask(workspaceId, userId, taskId);
        TaskDeliverable deliverable = findDeliverable(task, deliverableId);
        if (deliverable.isSubmitted()) {
            deliverable.unsubmit();
            task.refreshStatusByDeliverables();
        }
    }

    private Task lockTask(Long workspaceId, Long userId, Long taskId) {
        accessChecker.checkMember(workspaceId, userId);
        return taskRepository.findLockedByTaskIdAndWorkspaceId(taskId, workspaceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    private ContentRefDTO requireTeamContent(Long workspaceId, Long contentRefId) {
        ContentRefService.ContentOwner owner = contentRefService.getOwner(contentRefId);
        if (!workspaceId.equals(owner.workspaceId()) || owner.ownerUserId() != null || owner.isOriginDeleted()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return contentRefService.getContentRef(contentRefId);
    }

    private List<ContentRefDTO> candidates(Long workspaceId, Long userId, Provider provider) {
        return switch (provider) {
            case SLACK -> slackContentService.getContents(workspaceId);
            case NOTION -> notionContentService.getContents(workspaceId);
            case GOOGLE_DRIVE -> driveContentService.getFiles(workspaceId, userId, null);
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        };
    }

    private static TaskDeliverable findDeliverable(Task task, Long deliverableId) {
        return task.getDeliverables().stream()
            .filter(deliverable -> deliverableId.equals(deliverable.getDeliverableId()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static boolean contains(String value, String keyword) {
        return keyword.isEmpty() || value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }
}
