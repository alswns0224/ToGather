package com.togather.search.repository;

import com.togather.search.domain.ContentEmbedding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContentEmbeddingRepository extends JpaRepository<ContentEmbedding, Long> {

    /**
     * 임베딩을 새로 만들거나 지워야 하는 팀 콘텐츠(Slack·Notion·Drive) ID.
     * - 아직 임베딩하지 않은 살아 있는 콘텐츠
     * - 임베딩한 뒤 원본 수정 시각이 바뀐 콘텐츠
     * - 임베딩했지만 원본에서 지워졌거나 Drive 휴지통으로 옮겨진 콘텐츠
     */
    @Query(value = """
            SELECT c.content_ref_id FROM content_ref c
            LEFT JOIN content_embedding e ON e.content_ref_id = c.content_ref_id
            WHERE c.provider IN ('SLACK', 'NOTION', 'GOOGLE_DRIVE') AND c.owner_user_id IS NULL
              AND ((e.content_ref_id IS NULL AND NOT c.is_origin_deleted AND NOT c.drive_trashed)
                OR (e.content_ref_id IS NOT NULL
                    AND (c.is_origin_deleted OR c.drive_trashed
                         OR e.source_modified_at IS DISTINCT FROM COALESCE(c.last_modified_at, c.created_at))))
            ORDER BY c.content_ref_id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findStaleContentIds(int limit);

    /** 이 워크스페이스에서 아직 검색에 반영되지 않은 콘텐츠 수 (검색 화면 안내용) */
    @Query(value = """
            SELECT COUNT(*) FROM content_ref c
            LEFT JOIN content_embedding e ON e.content_ref_id = c.content_ref_id
            WHERE c.workspace_id = :workspaceId
              AND c.provider IN ('SLACK', 'NOTION', 'GOOGLE_DRIVE') AND c.owner_user_id IS NULL
              AND NOT c.is_origin_deleted AND NOT c.drive_trashed
              AND (e.content_ref_id IS NULL
                   OR e.source_modified_at IS DISTINCT FROM COALESCE(c.last_modified_at, c.created_at))
            """, nativeQuery = true)
    long countPending(Long workspaceId);
}
