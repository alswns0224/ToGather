package com.togather.analysis.repository;

import com.togather.analysis.domain.AnalysisEvidence;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisEvidenceRepository extends JpaRepository<AnalysisEvidence, Long> {

    List<AnalysisEvidence> findAllByItemIdIn(Collection<Long> itemIds);

    boolean existsByItemIdAndContentRefId(Long itemId, Long contentRefId);
}
