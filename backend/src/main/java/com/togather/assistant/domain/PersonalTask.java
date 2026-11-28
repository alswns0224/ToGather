package com.togather.assistant.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.PersonalTaskStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-20. 개인 할 일 (개인 데이터: ownerUserId 조건으로만 조회) */
@Getter
@Entity
@Table(name = "personal_task")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonalTask extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long personalTaskId;

    private Long ownerUserId;

    private String title;

    private LocalDate dueDate;

    /** 마감 시각. 없으면 그날 하루 종일 */
    private LocalTime dueTime;

    private Long sourceContentRefId;

    @Enumerated(EnumType.STRING)
    private PersonalTaskStatus status;

    public PersonalTask(Long ownerUserId, String title, LocalDate dueDate, Long sourceContentRefId) {
        this.ownerUserId = ownerUserId;
        this.title = title;
        this.dueDate = dueDate;
        this.sourceContentRefId = sourceContentRefId;
        this.status = PersonalTaskStatus.TODO;
    }

    public void complete() {
        this.status = PersonalTaskStatus.DONE;
    }

    /** 완료 취소 */
    public void reopen() {
        this.status = PersonalTaskStatus.TODO;
    }

    public void update(String title, LocalDate dueDate) {
        this.title = title;
        this.dueDate = dueDate;
    }

    /** 마감 시각. 마감일이 없으면 시각도 두지 않는다. */
    public void changeDueTime(LocalTime dueTime) {
        this.dueTime = dueDate == null ? null : dueTime;
    }
}
