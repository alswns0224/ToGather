package com.togather.analysis.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.AnalysisStatus;
import com.togather.common.enums.OutputStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-08 */
@Getter
@Entity
@Table(name = "discussion_analysis")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscussionAnalysis extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long analysisId;

    /** 분석 대상 Slack 스레드 */
    private Long contentRefId;

    @Enumerated(EnumType.STRING)
    private AnalysisStatus status;

    /** 분석 요청자. 자동 수집이면 null(시스템) */
    private Long requestedBy;

    private LocalDateTime analyzedUntil;

    private Long notionPageRefId;

    @Enumerated(EnumType.STRING)
    private OutputStatus notionOutputStatus;

    public DiscussionAnalysis(Long contentRefId, Long requestedBy) {
        this.contentRefId = contentRefId;
        this.requestedBy = requestedBy;
        this.status = AnalysisStatus.ANALYZING;
    }

    public void changeStatus(AnalysisStatus status) {
        this.status = status;
    }

    public void markAnalyzedUntil(LocalDateTime analyzedUntil) {
        this.analyzedUntil = analyzedUntil;
    }

    public void recordNotionOutput(Long notionPageRefId, OutputStatus outputStatus) {
        this.notionPageRefId = notionPageRefId;
        this.notionOutputStatus = outputStatus;
    }
}
