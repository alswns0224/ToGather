package com.togather.analysis.repository;

import com.togather.analysis.domain.DiscussionAnalysis;
import com.togather.common.enums.AnalysisStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DiscussionAnalysisRepository extends JpaRepository<DiscussionAnalysis, Long> {

    /** 스레드의 가장 최근 분석 */
    Optional<DiscussionAnalysis> findFirstByContentRefIdOrderByAnalysisIdDesc(Long contentRefId);

    List<DiscussionAnalysis> findAllByContentRefIdOrderByAnalysisIdAsc(Long contentRefId);

    /** 결정사항 정리로 만든 Notion 문서 구분 (UI-05) */
    List<DiscussionAnalysis> findAllByNotionPageRefIdIn(Collection<Long> notionPageRefIds);

    /** 워크스페이스의 특정 상태 분석 (스레드는 content_ref에 있으므로 워크스페이스로 함께 조회) */
    @Query("""
            select a from DiscussionAnalysis a, com.togather.content.domain.ContentRef c
            where a.contentRefId = c.contentRefId and c.workspaceId = :workspaceId and a.status in :statuses
            order by a.analysisId desc
            """)
    List<DiscussionAnalysis> findByWorkspaceAndStatus(Long workspaceId, Collection<AnalysisStatus> statuses);
}
