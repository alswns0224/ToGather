package com.togather.content.repository;

import com.togather.common.enums.Provider;
import com.togather.content.domain.ContentRef;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContentRefRepository extends JpaRepository<ContentRef, Long> {

    Optional<ContentRef> findByWorkspaceIdAndProviderAndOriginId(Long workspaceId, Provider provider, String originId);

    // ===== 개인 콘텐츠 (ownerUserId 조건 필수) =====

    List<ContentRef> findAllByOwnerUserIdAndProviderOrderByLastModifiedAtDesc(Long ownerUserId, Provider provider);

    List<ContentRef> findAllByOwnerUserIdAndProviderAndLastModifiedAtGreaterThanEqualOrderByLastModifiedAtDesc(
            Long ownerUserId, Provider provider, LocalDateTime since);

    List<ContentRef> findAllByWorkspaceIdAndOwnerUserIdAndProviderOrderByLastModifiedAtDesc(
            Long workspaceId, Long ownerUserId, Provider provider);

    List<ContentRef> findAllByWorkspaceIdAndOwnerUserIdAndProviderAndLastModifiedAtGreaterThanEqualOrderByLastModifiedAtDesc(
            Long workspaceId, Long ownerUserId, Provider provider, LocalDateTime since);

    /** 워크스페이스에서 연결한 내 개인 콘텐츠 (AI 업무 보조: Gmail) */
    List<ContentRef> findAllByWorkspaceIdAndOwnerUserIdAndProviderAndCreatedAtAfterOrderByCreatedAtDesc(
            Long workspaceId, Long ownerUserId, Provider provider, LocalDateTime after);

    // ===== 팀 콘텐츠 (Slack, Notion) =====

    /** 채널의 최상위 메시지 (답글 제외) */
    Page<ContentRef> findByWorkspaceIdAndProviderAndChannelIdAndParentRefIdIsNullOrderByCreatedAtDesc(
            Long workspaceId, Provider provider, String channelId, Pageable pageable);

    /** 채널의 최상위 메시지 중 원본에서 삭제되지 않은 것 (Slack 채널 화면) */
    Page<ContentRef> findByWorkspaceIdAndProviderAndChannelIdAndParentRefIdIsNullAndIsOriginDeletedFalseOrderByCreatedAtDesc(
            Long workspaceId, Provider provider, String channelId, Pageable pageable);

    List<ContentRef> findAllByParentRefIdOrderByCreatedAtAsc(Long parentRefId);

    /**
     * 원본에서 삭제된 답글 ID. 엔티티가 아니라 값으로 읽으므로, 같은 호출 안에서 먼저 읽어 둔 엔티티(1차 캐시)와 상관없이
     * DB의 최신 상태를 본다. (스레드 상세: 동기화 중 삭제 표시한 답글을 바로 빼야 함)
     */
    @Query("select c.contentRefId from ContentRef c where c.parentRefId = :parentRefId and c.isOriginDeleted = true")
    List<Long> findDeletedReplyIds(Long parentRefId);

    List<ContentRef> findAllByWorkspaceIdAndProviderAndParentRefIdIsNullOrderByLastModifiedAtDesc(
            Long workspaceId, Provider provider);

    List<ContentRef> findAllByWorkspaceIdAndProviderAndChannelIdAndIsOriginDeletedFalse(
            Long workspaceId, Provider provider, String channelId);

    /** 작성자 기준 (AI 업무 보조: 본인이 쓴 메시지) */
    List<ContentRef> findAllByWorkspaceIdInAndProviderAndAuthorExternalIdInAndCreatedAtAfterOrderByCreatedAtDesc(
            Collection<Long> workspaceIds, Provider provider, Collection<String> authorExternalIds,
            LocalDateTime after);

    /** 본문에 멘션(&lt;@U123&gt;)이 들어간 메시지 */
    @Query("""
            select c from ContentRef c
            where c.workspaceId in :workspaceIds and c.provider = :provider and c.createdAt > :after
              and c.content like :mentionPattern
            order by c.createdAt desc
            """)
    List<ContentRef> findByContentPattern(Collection<Long> workspaceIds, Provider provider, String mentionPattern,
                                          LocalDateTime after);

    /** since 이후 새 답글이 달렸거나 수정된 스레드(최상위 메시지) */
    @Query("""
            select p from ContentRef p
            where p.workspaceId = :workspaceId and p.provider = :provider and p.parentRefId is null
              and p.isOriginDeleted = false
              and (p.lastModifiedAt > :since
                   or exists (select 1 from ContentRef c where c.parentRefId = p.contentRefId and c.createdAt > :since))
            """)
    List<ContentRef> findUpdatedThreads(Long workspaceId, Provider provider, LocalDateTime since);

    long countByParentRefId(Long parentRefId);

    @Query("""
            select c.parentRefId as parentRefId, count(c) as count from ContentRef c
            where c.parentRefId in :parentRefIds group by c.parentRefId
            """)
    List<ReplyCount> countByParentRefIds(Collection<Long> parentRefIds);

    interface ReplyCount {

        Long getParentRefId();

        Long getCount();
    }
}
