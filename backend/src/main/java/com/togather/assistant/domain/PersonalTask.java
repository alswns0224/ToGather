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
}
