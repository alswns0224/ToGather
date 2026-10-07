package com.togather.analysis.domain;

import com.togather.common.domain.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-10. 분석 항목의 근거 발언 */
@Getter
@Entity
@Table(name = "analysis_evidence")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisEvidence extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long evidenceId;

    private Long itemId;

    private Long contentRefId;

    public AnalysisEvidence(Long itemId, Long contentRefId) {
        this.itemId = itemId;
        this.contentRefId = contentRefId;
    }
}
