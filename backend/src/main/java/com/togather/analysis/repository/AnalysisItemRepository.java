package com.togather.analysis.repository;

import com.togather.analysis.domain.AnalysisItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisItemRepository extends JpaRepository<AnalysisItem, Long> {
}
