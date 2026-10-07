package com.togather.analysis.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.OutputStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** C-00-09 */
@Getter
@Entity
@Table(name = "analysis_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long itemId;

    private Long analysisId;

    @Enumerated(EnumType.STRING)
    private ItemType itemType;

    private String content;

    private boolean isUndetermined;

    /** 생성된 Task 또는 회의의 ID */
    private Long outputTargetId;

    @Enumerated(EnumType.STRING)
    private OutputStatus outputStatus;

    private Long suggestedAssigneeId;

    private LocalDate suggestedDueDate;

    private LocalDateTime suggestedDatetime;

    /** 비어 있으면 스레드 참여자로 대체 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<Long> suggestedParticipantIds = new ArrayList<>();

    public AnalysisItem(Long analysisId, ItemType itemType, String content, boolean isUndetermined) {
        this.analysisId = analysisId;
        this.itemType = itemType;
        this.content = content;
        this.isUndetermined = isUndetermined;
    }

    public void update(String content, Long suggestedAssigneeId, LocalDate suggestedDueDate,
                       LocalDateTime suggestedDatetime, List<Long> suggestedParticipantIds) {
        this.content = content;
        this.suggestedAssigneeId = suggestedAssigneeId;
        this.suggestedDueDate = suggestedDueDate;
        this.suggestedDatetime = suggestedDatetime;
        this.suggestedParticipantIds = suggestedParticipantIds == null ? new ArrayList<>() : new ArrayList<>(suggestedParticipantIds);
    }

    public void recordOutput(Long outputTargetId, OutputStatus outputStatus) {
        this.outputTargetId = outputTargetId;
        this.outputStatus = outputStatus;
    }
}
