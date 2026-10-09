package com.togather.content.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.repository.ContentRefRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * content_ref 저장·조회 (docs/shared-interfaces.md, B 담당).
 * A의 Calendar 구현에 필요한 메서드만 먼저 만들어 두었다. B가 이어서 확장한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentRefService {

    private final ContentRefRepository contentRefRepository;

    /** workspaceId + provider + originId 기준으로 있으면 제목·본문을 갱신하고, 없으면 새로 저장한다. */
    @Transactional
    public ContentRefDTO upsert(ContentRefUpsert command) {
        ContentRef ref = contentRefRepository
                .findByWorkspaceIdAndProviderAndOriginId(command.workspaceId(), command.provider(), command.originId())
                .map(existing -> {
                    existing.updateContent(command.title(), command.content());
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
                        .build()));
        return ContentRefDTO.from(ref);
    }

    public ContentRefDTO getContentRef(Long contentRefId) {
        return ContentRefDTO.from(find(contentRefId));
    }

    public List<ContentRefDTO> getContentRefs(Collection<Long> contentRefIds) {
        return contentRefRepository.findAllById(contentRefIds).stream().map(ContentRefDTO::from).toList();
    }

    public Optional<ContentRefDTO> findByOrigin(Long workspaceId, Provider provider, String originId) {
        return contentRefRepository.findByWorkspaceIdAndProviderAndOriginId(workspaceId, provider, originId)
                .map(ContentRefDTO::from);
    }

    /** 콘텐츠가 속한 워크스페이스와 개인 콘텐츠 소유자 */
    public ContentOwner getOwner(Long contentRefId) {
        ContentRef ref = find(contentRefId);
        return new ContentOwner(ref.getWorkspaceId(), ref.getOwnerUserId(), ref.isOriginDeleted());
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

    private ContentRef find(Long contentRefId) {
        return contentRefRepository.findById(contentRefId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public record ContentRefUpsert(Long workspaceId, Long ownerUserId, Provider provider, ContentType contentType,
                                   String originId, String channelId, Long parentRefId,
                                   String title, String content, String deepLink, LocalDateTime createdAt) {
    }

    public record ContentOwner(Long workspaceId, Long ownerUserId, boolean isOriginDeleted) {
    }
}
