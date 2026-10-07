package com.togather.task.domain;

import com.togather.auth.domain.User;
import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.RiskLevel;
import com.togather.common.enums.TaskStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-11 */
@Getter
@Entity
@Table(name = "task")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long taskId;

    private Long workspaceId;

    private String title;

    private String description;

    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    /** 미지정 시 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    /** 생성 근거 결정사항(AnalysisItem) */
    private Long originAnalysisItemId;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TaskDeliverable> deliverables = new ArrayList<>();

    public Task(Long workspaceId, String title, String description, LocalDate dueDate, TaskStatus status) {
        this.workspaceId = workspaceId;
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.status = status == null ? TaskStatus.IN_PROGRESS : status;
    }

    public void linkOriginAnalysisItem(Long analysisItemId) {
        this.originAnalysisItemId = analysisItemId;
    }

    /** 진행률(%) = 제출된 산출물 / 필요 산출물. 산출물이 없으면 상태로 판단한다. */
    public int calculateProgress() {
        if (deliverables.isEmpty()) {
            return status == TaskStatus.DONE ? 100 : 0;
        }
        long submitted = deliverables.stream().filter(TaskDeliverable::isSubmitted).count();
        return (int) (submitted * 100 / deliverables.size());
    }

    /** TODO(C): 위험도 기준 확정 필요. 아래는 임시 기준이다. */
    public RiskLevel evaluateRisk(LocalDate today) {
        if (status == TaskStatus.DONE || dueDate == null) {
            return RiskLevel.NORMAL;
        }
        long daysLeft = ChronoUnit.DAYS.between(today, dueDate);
        int progress = calculateProgress();
        if (daysLeft < 0 || (daysLeft <= 2 && progress < 50)) {
            return RiskLevel.DANGER;
        }
        if (daysLeft <= 5 && progress < 80) {
            return RiskLevel.WARNING;
        }
        return RiskLevel.NORMAL;
    }

    public void assignTo(User assignee) {
        this.assignee = assignee;
    }

    public TaskDeliverable addDeliverable(String name, String ext) {
        TaskDeliverable deliverable = new TaskDeliverable(this, name, ext);
        deliverables.add(deliverable);
        return deliverable;
    }

    public void update(String title, String description, LocalDate dueDate) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
    }

    public void changeStatus(TaskStatus status) {
        this.status = status;
    }

    /** 산출물이 모두 제출되면 DONE, 하나라도 해제되면 IN_PROGRESS로 전환한다. */
    public void refreshStatusByDeliverables() {
        if (deliverables.isEmpty()) {
            return;
        }
        boolean allSubmitted = deliverables.stream().allMatch(TaskDeliverable::isSubmitted);
        this.status = allSubmitted ? TaskStatus.DONE : TaskStatus.IN_PROGRESS;
    }
}
