package com.togather.assistant.repository;

import com.togather.assistant.domain.Suggestion;
import com.togather.common.enums.SuggestionStatus;
import com.togather.common.enums.SuggestionType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 개인 데이터: 항상 ownerUserId 조건으로 조회한다. (README 10번) */
public interface SuggestionRepository extends JpaRepository<Suggestion, Long> {

    Optional<Suggestion> findBySuggestionIdAndOwnerUserId(Long suggestionId, Long ownerUserId);

    /** 워크스페이스(원본 메시지 기준)의 내 후보. type이 null이면 전체 */
    @Query("""
            select s from Suggestion s, ContentRef c
            where s.sourceContentRefId = c.contentRefId and c.workspaceId = :workspaceId
              and s.ownerUserId = :ownerUserId and s.status = :status and (:type is null or s.type = :type)
            order by s.createdAt desc
            """)
    List<Suggestion> findByOwnerInWorkspace(Long ownerUserId, Long workspaceId, SuggestionStatus status,
                                            SuggestionType type);

    /** 이미 후보를 뽑은 메시지 (다시 추출하지 않는다) */
    List<Suggestion> findAllByOwnerUserIdAndSourceContentRefIdIn(Long ownerUserId, Collection<Long> sourceIds);

    List<Suggestion> findAllByOwnerUserIdAndStatus(Long ownerUserId, SuggestionStatus status);
}
