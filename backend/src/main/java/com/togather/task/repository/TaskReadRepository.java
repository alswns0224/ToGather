package com.togather.task.repository;

import com.togather.task.dto.TaskFilter;
import com.togather.task.dto.TaskSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TaskReadRepository {

    private static final String SELECT = """
        select new com.togather.task.repository.TaskReadRow(
            t.taskId, t.workspaceId, t.title, t.assigneeId, t.dueDate, t.status,
            count(d.deliverableId), sum(case when d.isSubmitted = true then 1L else 0L end))
        from Task t left join t.deliverables d
        """;
    private static final String GROUP = """
         group by t.taskId, t.workspaceId, t.title, t.assigneeId, t.dueDate, t.status, t.createdAt
        """;
    private final EntityManager entityManager;

    public Page<TaskReadRow> findPage(Long workspaceId, TaskFilter filter, Pageable pageable) {
        QueryParts parts = where(workspaceId, filter);
        String order = filter.sort() == TaskSort.DUE_DATE
                ? " order by t.dueDate asc nulls last, t.createdAt desc, t.taskId desc"
                : " order by t.createdAt desc, t.taskId desc";
        List<TaskReadRow> rows = bind(entityManager.createQuery(SELECT + parts.where() + GROUP + order,
                TaskReadRow.class), parts.parameters())
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize()).getResultList();
        long total = bind(entityManager.createQuery("select count(t) from Task t " + parts.where(), Long.class),
                parts.parameters()).getSingleResult();
        return new PageImpl<>(rows, pageable, total);
    }

    public List<TaskReadRow> findAll(Long workspaceId, TaskFilter filter) {
        QueryParts parts = where(workspaceId, filter);
        return bind(entityManager.createQuery(SELECT + parts.where() + GROUP, TaskReadRow.class), parts.parameters())
                .getResultList();
    }

    private static QueryParts where(Long workspaceId, TaskFilter filter) {
        StringBuilder where = new StringBuilder(" where t.workspaceId = :workspaceId");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("workspaceId", workspaceId);
        if (filter.assigneeId() != null) {
            where.append(" and t.assigneeId = :assigneeId");
            parameters.put("assigneeId", filter.assigneeId());
        }
        if (filter.unassigned()) where.append(" and t.assigneeId is null");
        if (filter.status() != null) {
            where.append(" and t.status = :status");
            parameters.put("status", filter.status());
        }
        if (!filter.query().isEmpty()) {
            where.append(" and locate(:query, lower(t.title)) > 0");
            parameters.put("query", filter.query());
        }
        if (filter.dueBefore() != null) {
            where.append(" and t.dueDate < :dueBefore");
            parameters.put("dueBefore", filter.dueBefore());
        }
        return new QueryParts(where.toString(), parameters);
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
        return query;
    }

    private record QueryParts(String where, Map<String, Object> parameters) {
    }
}
