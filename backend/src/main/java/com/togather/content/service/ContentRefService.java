package com.togather.content.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;

import com.togather.content.domain.ContentRef;
import com.togather.content.domain.ContentRef.DriveFileState;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.repository.ContentRefRepository.ReplyCount;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * content_ref 저장·조회
 * (docs/shared-interfaces.md [결정 1], B 담당).
 *
 * A·C는 content_ref 테이블을 직접 쓰지 않고
 * 이 서비스만 호출한다.
 *
 * 지원 기능:
 * - 콘텐츠 신규 저장 및 갱신
 * - 콘텐츠 목록 및 상세 조회
 * - 개인 콘텐츠 조회
 * - 콘텐츠 수정
 * - 원본 삭제 상태 표시
 * - 원본 삭제 상태 복원
 * - 삭제된 원본 재동기화 시 자동 복원
 * - 동기화 실패 상태 기록
 * - 변경된 콘텐츠 수 집계
 * - 답글 수 집계
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentRefService {

    /**
     * countChanges 안에서 실제로 바뀐 콘텐츠 수.
     * 같은 스레드에서만 센다.
     */
    private static final ThreadLocal<int[]> CHANGES =
        new ThreadLocal<>();

    private final ContentRefRepository contentRefRepository;

    /**
     * work를 실행하는 동안 실제로 바뀐 콘텐츠 수를 센다.
     *
     * 집계 대상:
     * - 신규 콘텐츠 생성
     * - 제목 변경
     * - 본문 변경
     * - 상위 콘텐츠 변경
     * - 원본 삭제
     * - 원본 복원
     *
     * 같은 내용을 다시 저장한 경우에는 세지 않는다.
     */
    public static int countChanges(Runnable work) {

        int[] outer = CHANGES.get();
        int[] counter = {0};

        CHANGES.set(counter);

        try {
            work.run();

        } finally {

            if (outer != null) {
                outer[0] += counter[0];
                CHANGES.set(outer);

            } else {
                CHANGES.remove();
            }
        }

        return counter[0];
    }

    /**
     * 콘텐츠 변경 횟수 증가.
     */
    private static void changed() {

        int[] counter = CHANGES.get();

        if (counter != null) {
            counter[0]++;
        }
    }

    /**
     * workspaceId + provider + originId 기준 Upsert.
     *
     * 기존 콘텐츠가 있으면 최신 원본으로 갱신한다.
     * 기존 콘텐츠가 없으면 새로 저장한다.
     *
     * 추가 기능:
     * 기존 원본이 삭제 상태였으나 다시 수집되면
     * 삭제 상태를 자동으로 해제한다.
     *
     * Google Drive 휴지통 복원뿐만 아니라
     * 다른 서비스의 원본 재동기화에도 적용된다.
     */
    @Transactional
    public ContentRefDTO upsert(
        ContentRefUpsert command
    ) {

        boolean driveFile = command.provider() == Provider.GOOGLE_DRIVE
            && command.contentType() == ContentType.FILE;
        Optional<ContentRef> stored = driveFile
            ? contentRefRepository.findLockedByWorkspaceIdAndProviderAndOriginId(
                command.workspaceId(),
                command.provider(),
                command.originId()
            )
            : contentRefRepository.findByWorkspaceIdAndProviderAndOriginId(
                command.workspaceId(),
                command.provider(),
                command.originId()
            );

        ContentRef ref = stored
            .map(existing -> {

                // 1. 기존 콘텐츠의 변경 사항 확인
                boolean contentChanged =
                    !Objects.equals(
                        existing.getTitle(),
                        command.title()
                    )
                        || !Objects.equals(
                        existing.getContent(),
                        command.content()
                    )
                        || (
                        command.parentRefId() != null
                            && !Objects.equals(
                            existing.getParentRefId(),
                            command.parentRefId()
                        )
                    );

                // 2. 기존 삭제 상태 확인
                boolean wasDeleted =
                    existing.isOriginDeleted();
                boolean locationChanged = driveFile && existing.applyDriveLocation(
                    command.channelId(),
                    DriveFileState.AVAILABLE
                );

                // 3. 내용 변경 또는 복원 시 변경 횟수 증가
                if (contentChanged || wasDeleted || locationChanged) {
                    changed();
                }

                // 4. 최신 원본 메타데이터 적용
                existing.applySync(
                    command.title(),
                    command.content(),
                    command.deepLink(),
                    command.lastModifiedAt(),
                    command.authorExternalId(),
                    command.authorName()
                );

                // 5. 상위 콘텐츠 연결
                existing.attachToParent(
                    command.parentRefId()
                );

                // 6. 삭제된 원본이 재등장한 경우 복원
                if (wasDeleted && !driveFile) {
                    existing.restoreOriginDeleted();
                }

                return existing;
            })
            .orElseGet(() -> {

                // 7. 신규 콘텐츠 저장
                changed();

                return contentRefRepository.save(
                    ContentRef.builder()
                        .workspaceId(
                            command.workspaceId()
                        )
                        .ownerUserId(
                            command.ownerUserId()
                        )
                        .provider(
                            command.provider()
                        )
                        .contentType(
                            command.contentType()
                        )
                        .originId(
                            command.originId()
                        )
                        .channelId(
                            command.channelId()
                        )
                        .parentRefId(
                            command.parentRefId()
                        )
                        .title(
                            command.title()
                        )
                        .content(
                            command.content()
                        )
                        .deepLink(
                            command.deepLink()
                        )
                        .createdAt(
                            command.createdAt()
                        )
                        .lastModifiedAt(
                            command.lastModifiedAt()
                        )
                        .authorExternalId(
                            command.authorExternalId()
                        )
                        .authorName(
                            command.authorName()
                        )
                        .build()
                );
            });

        return ContentRefDTO.from(
            ref,
            countReplies(ref)
        );
    }

    @Transactional
    public void updateDriveLocation(
        Long workspaceId,
        String originId,
        String expectedFolderId,
        String folderId,
        DriveFileState state
    ) {
        contentRefRepository.findLockedByWorkspaceIdAndProviderAndOriginId(
                workspaceId, Provider.GOOGLE_DRIVE, originId
            )
            .filter(ref -> ref.getContentType() == ContentType.FILE)
            .filter(ref -> expectedFolderId == null || expectedFolderId.equals(ref.getChannelId()))
            .ifPresent(ref -> {
                if (ref.applyDriveLocation(folderId, state)) {
                    changed();
                }
            });
    }

    /**
     * 단일 콘텐츠 상세 조회.
     */
    public ContentRefDTO getContentRef(
        Long contentRefId
    ) {

        ContentRef ref = find(contentRefId);

        return ContentRefDTO.from(
            ref,
            countReplies(ref)
        );
    }

    /**
     * 여러 콘텐츠 조회.
     */
    public List<ContentRefDTO> getContentRefs(
        Collection<Long> contentRefIds
    ) {

        return toDtos(
            contentRefRepository.findAllById(
                contentRefIds
            )
        );
    }

    /** 협업 맥락에 필요한 생성 시각과 원본 상태를 같은 워크스페이스의 팀 콘텐츠만 조회한다. */
    public Map<Long, ContentState> getTeamContentStates(
        Long workspaceId,
        Collection<Long> contentRefIds
    ) {
        return contentRefRepository.findAllById(contentRefIds).stream()
            .filter(ref -> workspaceId.equals(ref.getWorkspaceId()))
            .filter(ref -> ref.getOwnerUserId() == null)
            .collect(Collectors.toMap(
                ContentRef::getContentRefId,
                ref -> new ContentState(ref.getCreatedAt(), ref.isOriginDeleted(), ref.isDriveTrashed())
            ));
    }

    public record ContentState(LocalDateTime createdAt, boolean originDeleted, boolean driveTrashed) {
    }

    /**
     * 원본 ID 기준 콘텐츠 조회.
     */
    public Optional<ContentRefDTO> findByOrigin(
        Long workspaceId,
        Provider provider,
        String originId
    ) {

        return contentRefRepository
            .findByWorkspaceIdAndProviderAndOriginId(
                workspaceId,
                provider,
                originId
            )
            .map(ref ->
                ContentRefDTO.from(
                    ref,
                    countReplies(ref)
                )
            );
    }

    /**
     * 콘텐츠가 속한 워크스페이스 및
     * 개인 콘텐츠 소유자 조회.
     */
    public ContentOwner getOwner(
        Long contentRefId
    ) {

        ContentRef ref = find(contentRefId);

        return new ContentOwner(
            ref.getWorkspaceId(),
            ref.getOwnerUserId(),
            ref.isOriginDeleted()
        );
    }

    /**
     * 개인 콘텐츠(Gmail, Calendar)를
     * 소유자 조건으로 조회한다.
     *
     * since가 null이면 전체 조회.
     * since가 지정되면 해당 시각 이후 수정된 콘텐츠 조회.
     */
    public List<ContentRefDTO> getPersonalContents(
        Long ownerUserId,
        Provider provider,
        LocalDateTime since
    ) {

        List<ContentRef> refs = since == null
            ? contentRefRepository
            .findAllByOwnerUserIdAndProviderOrderByLastModifiedAtDesc(
                ownerUserId,
                provider
            )
            : contentRefRepository
            .findAllByOwnerUserIdAndProviderAndLastModifiedAtGreaterThanEqualOrderByLastModifiedAtDesc(
                ownerUserId,
                provider,
                since
            );

        return toDtos(refs);
    }

    /**
     * 워크스페이스에 연결한 개인 콘텐츠를
     * 소유자 조건으로 조회한다.
     *
     * 다른 워크스페이스의 Gmail 및 Calendar
     * 콘텐츠가 섞이지 않도록 한다.
     */
    public List<ContentRefDTO> getPersonalContents(
        Long workspaceId,
        Long ownerUserId,
        Provider provider,
        LocalDateTime since
    ) {

        List<ContentRef> refs = since == null
            ? contentRefRepository
            .findAllByWorkspaceIdAndOwnerUserIdAndProviderOrderByLastModifiedAtDesc(
                workspaceId,
                ownerUserId,
                provider
            )
            : contentRefRepository
            .findAllByWorkspaceIdAndOwnerUserIdAndProviderAndLastModifiedAtGreaterThanEqualOrderByLastModifiedAtDesc(
                workspaceId,
                ownerUserId,
                provider,
                since
            );

        return toDtos(refs);
    }

    /**
     * 콘텐츠 제목 및 본문 수정.
     */
    @Transactional
    public void updateContent(
        Long contentRefId,
        String title,
        String content
    ) {

        find(contentRefId).updateContent(
            title,
            content
        );
    }

    /**
     * 원본 삭제 상태 표시.
     *
     * Google Drive의 경우:
     * 원본 파일을 휴지통으로 이동한 후 호출한다.
     *
     * 다른 서비스의 원본 삭제 동기화에서도 사용한다.
     */
    @Transactional
    public void markOriginDeleted(
        Long contentRefId
    ) {

        ContentRef ref = find(contentRefId);

        if (!ref.isOriginDeleted()) {

            ref.markOriginDeleted();

            changed();
        }
    }

    /**
     * 원본 삭제 상태 해제.
     *
     * Google Drive 휴지통 복원 시 사용할 수 있다.
     *
     * 이미 복원된 콘텐츠라면 아무것도 변경하지 않는다.
     */
    @Transactional
    public void restoreOriginDeleted(
        Long contentRefId
    ) {

        ContentRef ref = find(contentRefId);

        if (ref.isOriginDeleted()) {

            ref.restoreOriginDeleted();

            changed();
        }
    }

    /**
     * 동기화 중 콘텐츠 저장·갱신에 실패했음을 표시한다.
     *
     * 저장된 적 없는 콘텐츠면 아무것도 하지 않는다.
     */
    @Transactional
    public void markSyncFailed(
        Long workspaceId,
        Provider provider,
        String originId
    ) {

        contentRefRepository
            .findByWorkspaceIdAndProviderAndOriginId(
                workspaceId,
                provider,
                originId
            )
            .ifPresent(
                ContentRef::markSyncFailed
            );
    }

    /**
     * 여러 콘텐츠를 DTO로 변환한다.
     *
     * 답글 수는 한 번의 쿼리로 조회하여
     * N+1 문제를 방지한다.
     */
    public List<ContentRefDTO> toDtos(
        Collection<ContentRef> refs
    ) {

        if (refs.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> replyCounts =
            countReplies(
                refs.stream()
                    .map(ContentRef::getContentRefId)
                    .toList()
            );

        return refs.stream()
            .map(ref ->
                ContentRefDTO.from(
                    ref,
                    replyCounts.getOrDefault(
                        ref.getContentRefId(),
                        0L
                    ).intValue()
                )
            )
            .toList();
    }

    /**
     * 부모 콘텐츠 ID별 답글 수 조회.
     */
    public Map<Long, Long> countReplies(
        Collection<Long> parentRefIds
    ) {

        if (parentRefIds.isEmpty()) {
            return Map.of();
        }

        return contentRefRepository
            .countByParentRefIds(
                parentRefIds
            )
            .stream()
            .collect(
                Collectors.toMap(
                    ReplyCount::getParentRefId,
                    ReplyCount::getCount
                )
            );
    }

    /**
     * 단일 메시지의 답글 수 조회.
     */
    private int countReplies(
        ContentRef ref
    ) {

        return ref.getContentType()
            == ContentType.MESSAGE
            ? (int) contentRefRepository
            .countByParentRefIdAndIsOriginDeletedFalse(
                ref.getContentRefId()
            )
            : 0;
    }

    /**
     * ContentRef Entity 조회.
     *
     * 존재하지 않으면 RESOURCE_NOT_FOUND.
     */
    private ContentRef find(
        Long contentRefId
    ) {

        return contentRefRepository
            .findById(contentRefId)
            .orElseThrow(() ->
                new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND
                )
            );
    }

    /**
     * 저장할 콘텐츠.
     *
     * lastModifiedAt이 null이면
     * createdAt을 사용한다.
     *
     * 작성자 정보는 Slack 메시지 등
     * 작성자가 있는 콘텐츠만 채운다.
     */
    public record ContentRefUpsert(
        Long workspaceId,
        Long ownerUserId,
        Provider provider,
        ContentType contentType,
        String originId,
        String channelId,
        Long parentRefId,
        String title,
        String content,
        String deepLink,
        LocalDateTime createdAt,
        LocalDateTime lastModifiedAt,
        String authorExternalId,
        String authorName
    ) {

        /**
         * 수정 시각 및 작성자 없이 저장.
         *
         * docs/shared-interfaces.md의 기본 형태.
         */
        public ContentRefUpsert(
            Long workspaceId,
            Long ownerUserId,
            Provider provider,
            ContentType contentType,
            String originId,
            String channelId,
            Long parentRefId,
            String title,
            String content,
            String deepLink,
            LocalDateTime createdAt
        ) {

            this(
                workspaceId,
                ownerUserId,
                provider,
                contentType,
                originId,
                channelId,
                parentRefId,
                title,
                content,
                deepLink,
                createdAt,
                null,
                null,
                null
            );
        }
    }

    /**
     * 콘텐츠 소유자 정보.
     */
    public record ContentOwner(
        Long workspaceId,
        Long ownerUserId,
        boolean isOriginDeleted
    ) {}
}
