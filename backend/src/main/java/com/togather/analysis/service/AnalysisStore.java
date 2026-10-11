package com.togather.analysis.service;

import com.togather.analysis.domain.AnalysisEvidence;
import com.togather.analysis.domain.AnalysisItem;
import com.togather.analysis.domain.DiscussionAnalysis;
import com.togather.analysis.repository.AnalysisEvidenceRepository;
import com.togather.analysis.repository.AnalysisItemRepository;
import com.togather.analysis.repository.DiscussionAnalysisRepository;
import com.togather.common.enums.AnalysisStatus;
import com.togather.common.enums.ItemType;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 상태 저장. Gemini 호출(최대 30초) 동안 DB 트랜잭션을 잡지 않도록 단계마다 따로 커밋한다.
 * ANALYZING을 먼저 커밋해 두어 같은 스레드를 동시에 두 번 분석하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AnalysisStore {

    /** 서버가 분석 중에 꺼져 ANALYZING으로 남은 분석은 이 시간이 지나면 실패로 본다. */
    static final Duration STALE_ANALYZING = Duration.ofMinutes(3);

    private final DiscussionAnalysisRepository analysisRepository;
    private final AnalysisItemRepository itemRepository;
    private final AnalysisEvidenceRepository evidenceRepository;

    public record NewItem(ItemType itemType, String content, boolean undetermined, Long assigneeId,
                          LocalDate dueDate, LocalDateTime datetime, List<Long> participantIds,
                          List<Long> evidenceContentRefIds) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(Long contentRefId, Long requestedBy) {
        analysisRepository.findFirstByContentRefIdOrderByAnalysisIdDesc(contentRefId)
                .filter(a -> a.getStatus() == AnalysisStatus.ANALYZING)
                .ifPresent(running -> {
                    if (running.getUpdatedAt() != null
                            && running.getUpdatedAt().isAfter(LocalDateTime.now().minus(STALE_ANALYZING))) {
                        throw new BusinessException(ErrorCode.ANALYSIS_IN_PROGRESS);
                    }
                    running.changeStatus(AnalysisStatus.FAILED);
                });
        return analysisRepository.save(new DiscussionAnalysis(contentRefId, requestedBy)).getAnalysisId();
    }

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
        analysis.changeStatus(items.isEmpty() ? AnalysisStatus.NO_CONTENT : AnalysisStatus.REVIEWING);
    }

    /** 실패해도 분석 기록은 남긴다. 같은 범위(analyzedUntil 이후)를 다시 분석할 수 있다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long analysisId) {
        analysisRepository.findById(analysisId).ifPresent(a -> a.changeStatus(AnalysisStatus.FAILED));
    }
}
