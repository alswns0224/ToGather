package com.togather.analysis.repository;

import com.togather.analysis.domain.AnalysisItem;
import com.togather.common.enums.ItemType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisItemRepository extends JpaRepository<AnalysisItem, Long> {

    List<AnalysisItem> findAllByAnalysisIdOrderByItemIdAsc(Long analysisId);

    List<AnalysisItem> findAllByAnalysisIdIn(Collection<Long> analysisIds);

    List<AnalysisItem> findAllByAnalysisIdAndItemTypeOrderByItemIdAsc(Long analysisId, ItemType itemType);

    long countByAnalysisId(Long analysisId);
}
