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

public interface ContentRefRepository
    extends JpaRepository<ContentRef, Long> {

    Optional<ContentRef>
    findByWorkspaceIdAndProviderAndOriginId(
        Long workspaceId,
        Provider provider,
        String originId
    );

    // =================================================
    // 개인 콘텐츠 (ownerUserId 조건 필수)
    // =================================================

    List<ContentRef>
    findAllByOwnerUserIdAndProviderOrderByLastModifiedAtDesc(
        Long ownerUserId,
        Provider provider
    );

    List<ContentRef>
    findAllByOwnerUserIdAndProviderAndLastModifiedAtGreaterThanEqualOrderByLastModifiedAtDesc(
        Long ownerUserId,
        Provider provider,
        LocalDateTime since
    );

    List<ContentRef>
    findAllByWorkspaceIdAndOwnerUserIdAndProviderOrderByLastModifiedAtDesc(
        Long workspaceId,
        Long ownerUserId,
        Provider provider
    );

    List<ContentRef>
    findAllByWorkspaceIdAndOwnerUserIdAndProviderAndLastModifiedAtGreaterThanEqualOrderByLastModifiedAtDesc(
        Long workspaceId,
        Long ownerUserId,
        Provider provider,
        LocalDateTime since
    );

    /**
     * 워크스페이스에서 연결한 내 개인 콘텐츠.
     * AI 업무 보조: Gmail.
     */
    List<ContentRef>
    findAllByWorkspaceIdAndOwnerUserIdAndProviderAndCreatedAtAfterOrderByCreatedAtDesc(
        Long workspaceId,
        Long ownerUserId,
        Provider provider,
        LocalDateTime after
    );

    // =================================================
    // 팀 콘텐츠 (Slack, Notion, Google Drive)
    // =================================================

    /**
     * 채널의 최상위 메시지 (답글 제외).
     */
    Page<ContentRef>
    findByWorkspaceIdAndProviderAndChannelIdAndParentRefIdIsNullOrderByCreatedAtDesc(
        Long workspaceId,
        Provider provider,
        String channelId,
        Pageable pageable
    );

    /**
     * 원본에서 삭제되지 않은 최상위 메시지.
     * Slack 채널 화면.
     */
    Page<ContentRef>
    findByWorkspaceIdAndProviderAndChannelIdAndParentRefIdIsNullAndIsOriginDeletedFalseOrderByCreatedAtDesc(
        Long workspaceId,
        Provider provider,
        String channelId,
        Pageable pageable
    );

    List<ContentRef>
    findAllByParentRefIdOrderByCreatedAtAsc(
        Long parentRefId
    );

    /**
     * 원본에서 삭제된 답글 ID 조회.
     * DB의 최신 삭제 상태를 확인한다.
     */
    @Query("""
        select c.contentRefId
        from ContentRef c
        where c.parentRefId = :parentRefId
          and c.isOriginDeleted = true
        """)
    List<Long> findDeletedReplyIds(
        Long parentRefId
    );

    /**
     * 워크스페이스·Provider별 최상위 콘텐츠.
     *
     * Google Drive:
     * 휴지통 파일 조회 시 사용한다.
     * Service에서 isOriginDeleted를 확인한다.
     */
    List<ContentRef>
    findAllByWorkspaceIdAndProviderAndParentRefIdIsNullOrderByLastModifiedAtDesc(
        Long workspaceId,
        Provider provider
    );

    /**
     * 선택한 채널·폴더의 삭제되지 않은 콘텐츠.
     *
     * Google Drive:
     * 연동한 폴더의 일반 파일 목록 조회.
     */
    List<ContentRef>
    findAllByWorkspaceIdAndProviderAndChannelIdAndIsOriginDeletedFalse(
        Long workspaceId,
        Provider provider,
        String channelId
    );

    /**
     * 작성자 기준 콘텐츠 조회.
     * AI 업무 보조: 본인이 작성한 메시지.
     */
    List<ContentRef>
    findAllByWorkspaceIdInAndProviderAndAuthorExternalIdInAndCreatedAtAfterOrderByCreatedAtDesc(
        Collection<Long> workspaceIds,
        Provider provider,
        Collection<String> authorExternalIds,
        LocalDateTime after
    );

    /**
     * 본문에 멘션이 들어간 메시지.
     */
    @Query("""
        select c
        from ContentRef c
        where c.workspaceId in :workspaceIds
          and c.provider = :provider
          and c.createdAt > :after
          and c.content like :mentionPattern
        order by c.createdAt desc
        """)
    List<ContentRef> findByContentPattern(
        Collection<Long> workspaceIds,
        Provider provider,
        String mentionPattern,
        LocalDateTime after
    );

    /**
     * since 이후 수정되었거나
     * 새 답글이 달린 최상위 스레드.
     */
    @Query("""
        select p
        from ContentRef p
        where p.workspaceId = :workspaceId
          and p.provider = :provider
          and p.parentRefId is null
          and p.isOriginDeleted = false
          and (
              p.lastModifiedAt > :since
              or exists (
                  select 1
                  from ContentRef c
                  where c.parentRefId = p.contentRefId
                    and c.createdAt > :since
              )
          )
        """)
    List<ContentRef> findUpdatedThreads(
        Long workspaceId,
        Provider provider,
        LocalDateTime since
    );

    // =================================================
    // 답글 수 집계
    // =================================================

    /**
     * 원본에서 삭제되지 않은 답글 수.
     */
    long countByParentRefIdAndIsOriginDeletedFalse(
        Long parentRefId
    );

    @Query("""
        select c.parentRefId as parentRefId,
               count(c) as count
        from ContentRef c
        where c.parentRefId in :parentRefIds
          and c.isOriginDeleted = false
        group by c.parentRefId
        """)
    List<ReplyCount> countByParentRefIds(
        Collection<Long> parentRefIds
    );

    interface ReplyCount {

        Long getParentRefId();

        Long getCount();
    }
}
