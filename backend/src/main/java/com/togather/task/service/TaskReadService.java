package com.togather.task.service;

import com.togather.common.enums.RiskLevel;
import com.togather.common.enums.TaskStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.common.response.PageResponse;
import com.togather.task.dto.TaskFilter;
import com.togather.task.dto.TaskProgressResponse;
import com.togather.task.dto.TaskProgressResponse.AssigneeProgress;
import com.togather.task.dto.TaskProgressResponse.Summary;
import com.togather.task.dto.TaskSort;
import com.togather.task.dto.TaskSummary;
import com.togather.task.repository.TaskReadRepository;
import com.togather.workspace.service.WorkspaceMemberService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TaskReadService {

    private final TaskReadRepository repository;
    private final WorkspaceMemberService members;

    public PageResponse<TaskSummary> getPage(Long workspaceId, Long userId, boolean mine, TaskFilter filter,
                                              int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        TaskFilter effective = resolve(userId, mine, filter);
        LocalDate today = TaskService.currentTaskDate();
        return PageResponse.from(repository.findPage(workspaceId, effective, PageRequest.of(page, size)),
                row -> row.summary(today));
    }

    public TaskProgressResponse getProgress(Long workspaceId, Long userId, boolean mine, TaskFilter filter) {
        TaskFilter effective = resolve(userId, mine, filter);
        LocalDate today = TaskService.currentTaskDate();
        List<TaskSummary> tasks = repository.findAll(workspaceId, effective).stream()
                .map(row -> row.summary(today)).toList();
        Map<Long, List<TaskSummary>> groups = new HashMap<>();
        if (effective.assigneeId() != null) groups.put(effective.assigneeId(), new ArrayList<>());
        else if (!effective.unassigned()) members.getMemberUserIds(workspaceId)
                .forEach(id -> groups.put(id, new ArrayList<>()));
        if (effective.assigneeId() == null) groups.put(null, new ArrayList<>());
        tasks.forEach(task -> groups.computeIfAbsent(task.assigneeId(), ignored -> new ArrayList<>()).add(task));
        List<AssigneeProgress> byAssignee = groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsLast(Comparator.naturalOrder())))
                .map(entry -> new AssigneeProgress(entry.getKey(), summarize(entry.getValue()))).toList();
        return new TaskProgressResponse(today, summarize(tasks), byAssignee);
    }

    private static TaskFilter resolve(Long userId, boolean mine, TaskFilter filter) {
        if ((filter.assigneeId() != null && filter.assigneeId() <= 0)
                || (filter.unassigned() && (mine || filter.assigneeId() != null))
                || (mine && filter.assigneeId() != null && !userId.equals(filter.assigneeId()))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "내 담당·특정 담당자·미지정 조건은 하나만 선택해 주세요.");
        }
        return new TaskFilter(mine ? userId : filter.assigneeId(), filter.unassigned(), filter.status(),
                filter.query() == null ? "" : filter.query().strip().toLowerCase(Locale.ROOT), filter.dueBefore(),
                filter.sort() == null ? TaskSort.RECENT : filter.sort());
    }

    private static Summary summarize(List<TaskSummary> tasks) {
        long done = tasks.stream().filter(task -> task.status() == TaskStatus.DONE).count();
        long normal = tasks.stream().filter(task -> task.riskLevel() == RiskLevel.NORMAL).count();
        long warning = tasks.stream().filter(task -> task.riskLevel() == RiskLevel.WARNING).count();
        int progress = tasks.isEmpty() ? 0 : (int) (tasks.stream().mapToLong(TaskSummary::progress).sum() / tasks.size());
        return new Summary(tasks.size(), tasks.size() - done, done, progress, normal, warning,
                tasks.size() - normal - warning);
    }
}
