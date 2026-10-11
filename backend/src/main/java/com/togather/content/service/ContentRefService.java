package com.togather.content.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.repository.ContentRefRepository.ReplyCount;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * content_ref 저장·조회 (docs/shared-interfaces.md [결정 1], B 담당).
 * A·C는 content_ref 테이블을 직접 쓰지 않고 이 서비스만 호출한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentRefService {

    private final ContentRefRepository contentRefRepository;

    /** workspaceId + provider + originId 기준으로 있으면 최신 원본으로 갱신하고, 없으면 새로 저장한다. */
    @Transactional
    public ContentRefDTO upsert(ContentRefUpsert command) {
        ContentRef ref = contentRefRepository
                .findByWorkspaceIdAndProviderAndOriginId(command.workspaceId(), command.provider(), command.originId())
                .map(existing -> {
                    existing.applySync(command.title(), command.content(), command.deepLink(),
                            command.lastModifiedAt(), command.authorExternalId(), command.authorName());
                    return existing;
                })
                .orElseGet(() -> contentRefRepository.save(ContentRef.builder()
                        .workspaceId(command.workspaceId())
                        .ownerUserId(command.ownerUserId())
                        .provider(command.provider())
                        .contentType(command.contentType())
                        .originId(command.originId())
                        .channelId(command.channelId())
                        .parentRefId(command.parentRefId())
                        .title(command.title())
                        .content(command.content())
                        .deepLink(command.deepLink())
                        .createdAt(command.createdAt())
                        .lastModifiedAt(command.lastModifiedAt())
                        .authorExternalId(command.authorExternalId())
                        .authorName(command.authorName())
                        .build()));
        return ContentRefDTO.from(ref, countReplies(ref));
    }

    public ContentRefDTO getContentRef(Long contentRefId) {
        ContentRef ref = find(contentRefId);
        return ContentRefDTO.from(ref, countReplies(ref));
    }

    public List<ContentRefDTO> getContentRefs(Collection<Long> contentRefIds) {
        return toDtos(contentRefRepository.findAllById(contentRefIds));
    }

    public Optional<ContentRefDTO> findByOrigin(Long workspaceId, Provider provider, String originId) {
        return contentRefRepository.findByWorkspaceIdAndProviderAndOriginId(workspaceId, provider, originId)
                .map(ref -> ContentRefDTO.from(ref, countReplies(ref)));
    }

    /** 콘텐츠가 속한 워크스페이스와 개인 콘텐츠 소유자 */
    public ContentOwner getOwner(Long contentRefId) {
        ContentRef ref = find(contentRefId);
        return new ContentOwner(ref.getWorkspaceId(), ref.getOwnerUserId(), ref.isOriginDeleted());
    }

    /**
     * 개인 콘텐츠(Gmail, Calendar)를 소유자 조건으로 조회한다. (README 10번)
     *
     * @param since 이 시각 이후에 수정된 것만. null이면 전체
     */
    public List<ContentRefDTO> getPersonalContents(Long ownerUserId, Provider provider, LocalDateTime since) {
        List<ContentRef> refs = since == null
                ? contentRefRepository.findAllByOwnerUserIdAndProviderOrderByLastModifiedAtDesc(ownerUserId, provider)
                : contentRefRepository
                        .findAllByOwnerUserIdAndProviderAndLastModifiedAtGreaterThanEqualOrderByLastModifiedAtDesc(
                                ownerUserId, provider, since);
        return toDtos(refs);
    }

    @Transactional
    public void updateContent(Long contentRefId, String title, String content) {
        find(contentRefId).updateContent(title, content);
    }

    @Transactional
    public void markOriginDeleted(Long contentRefId) {
        ContentRef ref = find(contentRefId);
        if (!ref.isOriginDeleted()) {
            ref.markOriginDeleted();
        }
    }

    /** 동기화 중 이 콘텐츠의 저장·갱신에 실패했음을 표시한다. 저장된 적 없는 콘텐츠면 아무것도 하지 않는다. */
    @Transactional
    public void markSyncFailed(Long workspaceId, Provider provider, String originId) {
        contentRefRepository.findByWorkspaceIdAndProviderAndOriginId(workspaceId, provider, originId)
                .ifPresent(ContentRef::markSyncFailed);
    }

    /** 여러 콘텐츠를 DTO로 바꾼다. 답글 수는 한 번의 쿼리로 센다. (N+1 방지) */
    public List<ContentRefDTO> toDtos(Collection<ContentRef> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> replyCounts = countReplies(refs.stream().map(ContentRef::getContentRefId).toList());
        return refs.stream()
                .map(ref -> ContentRefDTO.from(ref, replyCounts.getOrDefault(ref.getContentRefId(), 0L).intValue()))
                .toList();
    }

    /** 부모 콘텐츠 ID별 답글 수 */
    public Map<Long, Long> countReplies(Collection<Long> parentRefIds) {
        if (parentRefIds.isEmpty()) {
            return Map.of();
        }
        return contentRefRepository.countByParentRefIds(parentRefIds).stream()
                .collect(Collectors.toMap(ReplyCount::getParentRefId, ReplyCount::getCount));
    }

    private int countReplies(ContentRef ref) {
        return ref.getContentType() == ContentType.MESSAGE
                ? (int) contentRefRepository.countByParentRefId(ref.getContentRefId())
                : 0;
    }

    private ContentRef find(Long contentRefId) {
        return contentRefRepository.findById(contentRefId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 저장할 콘텐츠. lastModifiedAt이 null이면 createdAt을 쓴다.
     * 작성자(authorExternalId, authorName)는 Slack 메시지처럼 작성자가 있는 콘텐츠만 채운다.
     */
    public record ContentRefUpsert(Long workspaceId, Long ownerUserId, Provider provider, ContentType contentType,
                                   String originId, String channelId, Long parentRefId,
                                   String title, String content, String deepLink, LocalDateTime createdAt,
                                   LocalDateTime lastModifiedAt, String authorExternalId, String authorName) {

        /** 수정 시각·작성자 없이 저장 (docs/shared-interfaces.md의 기본 형태) */
        public ContentRefUpsert(Long workspaceId, Long ownerUserId, Provider provider, ContentType contentType,
                                String originId, String channelId, Long parentRefId,
                                String title, String content, String deepLink, LocalDateTime createdAt) {
            this(workspaceId, ownerUserId, provider, contentType, originId, channelId, parentRefId,
                    title, content, deepLink, createdAt, null, null, null);
        }
    }

    public record ContentOwner(Long workspaceId, Long ownerUserId, boolean isOriginDeleted) {
    }
}
