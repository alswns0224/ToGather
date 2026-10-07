package com.togather.analysis.repository;

import com.togather.analysis.domain.DiscussionAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscussionAnalysisRepository extends JpaRepository<DiscussionAnalysis, Long> {
}
