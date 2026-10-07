package com.togather.task.domain;

import com.togather.common.domain.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-12 */
@Getter
@Entity
@Table(name = "task_deliverable")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDeliverable extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long deliverableId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    private String name;

    /** 허용 확장자 (예: "pdf") */
    private String ext;

    private String fileId;

    private String fileName;

    private String link;

    private Long submittedBy;

    private LocalDateTime submittedAt;

    private boolean isSubmitted;

    TaskDeliverable(Task task, String name, String ext) {
        this.task = task;
        this.name = name;
        this.ext = ext;
    }

    public boolean acceptsExtension(String fileName) {
        if (ext == null || ext.isBlank()) {
            return true;
        }
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot >= 0 && fileName.substring(dot + 1).equalsIgnoreCase(ext);
    }

    public void submit(String fileId, String fileName, String link, Long by) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.link = link;
        this.submittedBy = by;
        this.submittedAt = LocalDateTime.now();
        this.isSubmitted = true;
    }

    /** 파일 정보는 유지하고 제출만 해제한다. */
    public void unsubmit() {
        this.isSubmitted = false;
    }
}
