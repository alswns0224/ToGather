package com.togather.assistant.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.SuggestionStatus;
import com.togather.common.enums.SuggestionType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-19. AI가 추출한 일정·할 일 후보 (개인 데이터: ownerUserId 조건으로만 조회) */
@Getter
@Entity
@Table(name = "suggestion")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Suggestion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long suggestionId;

    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    private SuggestionType type;

    @Enumerated(EnumType.STRING)
    private SuggestionStatus status;

    private Long sourceContentRefId;

    private String title;

    /** 불명확하면 null (시간 미정) */
    private LocalDateTime detectedDatetime;

    private LocalDate dueDate;

    private Long createdEventId;

    private Long createdPersonalTaskId;

    public Suggestion(Long ownerUserId, SuggestionType type, Long sourceContentRefId, String title,
                      LocalDateTime detectedDatetime, LocalDate dueDate) {
        this.ownerUserId = ownerUserId;
        this.type = type;
        this.sourceContentRefId = sourceContentRefId;
        this.title = title;
        this.detectedDatetime = detectedDatetime;
        this.dueDate = dueDate;
        this.status = SuggestionStatus.PENDING;
    }

    public void update(String title, LocalDateTime detectedDatetime, LocalDate dueDate) {
        this.title = title;
        this.detectedDatetime = detectedDatetime;
        this.dueDate = dueDate;
    }

    public void approveAsEvent(Long createdEventId) {
        this.createdEventId = createdEventId;
        this.status = SuggestionStatus.APPROVED;
    }

    public void approveAsPersonalTask(Long createdPersonalTaskId) {
        this.createdPersonalTaskId = createdPersonalTaskId;
        this.status = SuggestionStatus.APPROVED;
    }

    public void dismiss() {
        this.status = SuggestionStatus.DISMISSED;
    }
}
