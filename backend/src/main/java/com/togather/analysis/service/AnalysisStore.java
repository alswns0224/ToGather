package com.togather.analysis.service;

import com.togather.analysis.domain.AnalysisEvidence;
import com.togather.analysis.domain.AnalysisItem;
import com.togather.analysis.domain.DiscussionAnalysis;
import com.togather.analysis.repository.AnalysisEvidenceRepository;
import com.togather.analysis.repository.AnalysisItemRepository;
import com.togather.analysis.repository.DiscussionAnalysisRepository;
import com.togather.common.enums.AnalysisStatus;
import com.togather.common.enums.ItemType;
import com.togather.common.enums.OutputStatus;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 상태 저장. Gemini 호출(최대 30초) 동안 DB 트랜잭션을 잡지 않도록 단계마다 따로 커밋한다.
 * ANALYZING을 먼저 커밋해 두어 화면에서 분석 중임을 알 수 있게 한다.
 */
@Component
@RequiredArgsConstructor
public class AnalysisStore {

    private final DiscussionAnalysisRepository analysisRepository;
    private final AnalysisItemRepository itemRepository;
    private final AnalysisEvidenceRepository evidenceRepository;

    public record NewItem(ItemType itemType, String content, boolean undetermined, Long assigneeId,
                          LocalDate dueDate, LocalDateTime datetime, List<Long> participantIds,
                          List<Long> evidenceContentRefIds) {
    }

    /**
     * 분석을 시작한다. 확정 전 분석(검토 중·정리할 내용 없음·실패)이 있으면 새로 만들지 않고 그 분석에 이어서
     * 항목을 추가한다. 그래야 검토하던 항목이 목록에서 사라지지 않는다.
     *
     * @param reuseAnalysisId 이어서 쓸 분석. null이면 새로 만든다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Begin begin(Long reuseAnalysisId, Long contentRefId, Long requestedBy) {
        if (reuseAnalysisId != null) {
            DiscussionAnalysis analysis = analysisRepository.findById(reuseAnalysisId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
            AnalysisStatus previous = analysis.getStatus();
            analysis.changeStatus(AnalysisStatus.ANALYZING);
            return new Begin(reuseAnalysisId, previous, true);
        }
        Long id = analysisRepository.save(new DiscussionAnalysis(contentRefId, requestedBy)).getAnalysisId();
        return new Begin(id, null, false);
    }

    /** @param previousStatus 이어서 쓴 분석의 원래 상태 (새 분석이면 null) */
    public record Begin(Long analysisId, AnalysisStatus previousStatus, boolean reused) {
    }

    /** 새 항목을 추가하고, 항목이 하나라도 있으면 검토 대기로 둔다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveResult(Long analysisId, List<NewItem> items, LocalDateTime analyzedUntil) {
        DiscussionAnalysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
        for (NewItem n : items) {
            AnalysisItem item = new AnalysisItem(analysisId, n.itemType(), n.content(), n.undetermined());
            item.update(n.content(), n.assigneeId(), n.dueDate(), n.datetime(), n.participantIds());
            Long itemId = itemRepository.save(item).getItemId();
            evidenceRepository.saveAll(n.evidenceContentRefIds().stream()
                    .map(refId -> new AnalysisEvidence(itemId, refId))
                    .toList());
        }
        analysis.markAnalyzedUntil(analyzedUntil);
        boolean hasItems = itemRepository.countByAnalysisId(analysisId) > 0;
        analysis.changeStatus(hasItems ? AnalysisStatus.REVIEWING : AnalysisStatus.NO_CONTENT);
    }

    /**
     * 분석 실패. 새 분석이면 FAILED로 남기고(같은 범위를 다시 분석할 수 있음),
     * 이어서 쓰던 분석이면 원래 상태로 되돌려 검토하던 항목을 그대로 둔다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Begin begin) {
        analysisRepository.findById(begin.analysisId()).ifPresent(a -> {
            if (!begin.reused()) {
                a.changeStatus(AnalysisStatus.FAILED);
                return;
            }
            boolean hasItems = itemRepository.countByAnalysisId(a.getAnalysisId()) > 0;
            AnalysisStatus previous = begin.previousStatus();
            a.changeStatus(hasItems ? AnalysisStatus.REVIEWING
                    : previous == null || previous == AnalysisStatus.ANALYZING ? AnalysisStatus.FAILED : previous);
        });
    }

    /** 연계 결과(Task·Calendar)를 단계별로 기록한다. 실패해도 남아야 하므로 별도 트랜잭션 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordItemOutput(Long itemId, Long outputTargetId, OutputStatus status) {
        itemRepository.findById(itemId).ifPresent(item -> item.recordOutput(
                status == OutputStatus.SUCCESS ? outputTargetId : item.getOutputTargetId(), status));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNotionOutput(Long analysisId, Long notionPageRefId, OutputStatus status) {
        analysisRepository.findById(analysisId).ifPresent(a -> a.recordNotionOutput(
                status == OutputStatus.SUCCESS ? notionPageRefId : a.getNotionPageRefId(), status));
    }

}
