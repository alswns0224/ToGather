package com.togather.content.drive.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;

import com.togather.content.domain.ContentRef;
import com.togather.content.domain.ContentRef.DriveFileState;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveChange;
import com.togather.content.drive.client.DriveApiClient.DriveFile;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.service.TeamTokenResolver;

import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * Google Drive 폴더별 동기화 어댑터 (C-03).
 *
 * 주요 기능:
 *
 * 1. 선택된 Drive 폴더 조회
 * 2. 최초 전체 동기화
 * 3. 변경 토큰 기반 증분 동기화
 * 4. 파일 생성·수정 반영
 * 5. 파일 삭제·휴지통 이동 감지
 * 6. 폴더 외부로 이동한 파일 감지
 * 7. 삭제 후 재등장한 파일 복원
 * 8. 변경 토큰 만료 시 전체 재동기화
 *
 * Google Drive 파일 원본은 저장하지 않고
 * ContentRef에 메타데이터만 저장한다.
 *
 * 변경 이벤트 발행 및 실행 이력 관리는
 * 기존 SyncService에서 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriveSyncAdapter implements SyncAdapter {

    private static final Provider PROVIDER =
        Provider.GOOGLE_DRIVE;

    // ContentRef.content의 기본 DB 문자열 길이를 고려
    private static final int MAX_CONTENT_LENGTH = 240;

    private final DriveApiClient driveApiClient;
    private final ContentRefService contentRefService;
    private final ContentRefRepository contentRefRepository;
    private final IntegrationService integrationService;
    private final TeamTokenResolver teamTokenResolver;

    // =====================================================
    // 1. Provider
    // =====================================================

    @Override
    public Provider provider() {
        return PROVIDER;
    }

    // =====================================================
    // 2. 동기화 대상 폴더 조회
    // =====================================================

    /**
     * 워크스페이스에서 연동한 Google Drive 폴더를
     * 동기화 대상으로 등록한다.
     *
     * 동일 폴더가 중복 선택된 경우 한 번만 처리한다.
     */
    @Override
    public List<SyncTarget> findTargets(
        Long workspaceId,
        Long requestedBy
    ) {

        return integrationService
            .getConnectionTargets(
                workspaceId,
                PROVIDER
            )
            .stream()
            .filter(
                id -> id != null && !id.isBlank()
            )
            .distinct()
            .map(SyncTarget::team)
            .toList();
    }

    // =====================================================
    // 3. 동기화 진입점
    // =====================================================

    /**
     * 기존 SyncService가 호출하는 동기화 진입점.
     *
     * syncCursor가 없으면 최초 동기화.
     * syncCursor가 있으면 증분 동기화.
     *
     * 토큰이 만료되면 전체 재동기화한다.
     */
    @Override
    public SyncResult sync(
        SyncContext context
    ) {

        String folderId = context.targetId();

        if (folderId == null || folderId.isBlank()) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        // 프로젝트 공통 DB 시간 기준: UTC
        LocalDateTime startedAt = LocalDateTime.now();

        // 기존 공통 Google OAuth 토큰 관리 재사용
        String token = teamTokenResolver.tokenFor(
            context.workspaceId(),
            PROVIDER,
            folderId,
            context.requestedBy()
        );

        String cursor = context.syncCursor();

        // 최초 동기화
        if (cursor == null || cursor.isBlank()) {

            return initialSync(
                context.workspaceId(),
                folderId,
                token,
                startedAt
            );
        }

        // 증분 동기화
        try {

            return incrementalSync(
                context.workspaceId(),
                folderId,
                token,
                cursor,
                startedAt
            );

        } catch (
            DriveApiClient.CursorExpiredException e
        ) {

            log.warn(
                "Drive cursor expired. "
                    + "Full resync: workspaceId={}, folderId={}",
                context.workspaceId(),
                folderId
            );

            // 기존 변경 토큰 만료 시 전체 재동기화
            return initialSync(
                context.workspaceId(),
                folderId,
                token,
                startedAt
            );
        }
    }

    // =====================================================
    // 4. 최초 전체 동기화
    // =====================================================

    /**
     * 최초 동기화 및 커서 만료 시 전체 재동기화.
     *
     * 실행 순서:
     *
     * 1. 변경 이력 시작 토큰 확보
     * 2. 선택된 폴더의 전체 파일 목록 조회
     * 3. ContentRef 저장 및 갱신
     * 4. 휴지통 및 폴더 밖으로 이동한 파일의 상태 확인
     * 5. 변경 토큰 반환
     *
     * 파일 목록을 조회하기 전에 토큰을 확보하여
     * 조회 도중 발생한 변경도 다음 증분 동기화에서
     * 감지할 수 있도록 한다.
     */
    private SyncResult initialSync(
        Long workspaceId,
        String folderId,
        String token,
        LocalDateTime startedAt
    ) {

        // 1. 변경 토큰 먼저 확보
        String cursor =
            driveApiClient.getStartPageToken(token);

        // 2. 선택 폴더의 현재 파일 조회
        List<DriveFile> files =
            driveApiClient.listFolderFiles(
                token,
                folderId
            );

        Set<String> currentIds =
            new HashSet<>();

        int saved = 0;

        // 3. 파일 메타데이터 저장
        for (DriveFile file : files) {

            if (file == null
                || !file.isDirectChildOf(folderId)) {

                throw new BusinessException(
                    ErrorCode.EXTERNAL_API_ERROR
                );
            }

            saveFile(
                workspaceId,
                folderId,
                file,
                startedAt
            );

            currentIds.add(file.id());
            saved++;
        }

        for (DriveFile file : driveApiClient.listTrashedFolderFiles(token, folderId)) {
            if (currentIds.add(file.id())) {
                contentRefService.updateDriveLocation(
                    workspaceId, file.id(), null, folderId, DriveFileState.TRASHED
                );
            }
        }

        int reconciled = 0;
        for (ContentRef ref : getTrackedFiles(workspaceId, folderId)) {
            if (!currentIds.contains(ref.getOriginId())) {
                reconcileLocation(
                    workspaceId, folderId, ref.getOriginId(), lookupFile(token, ref.getOriginId())
                );
                reconciled++;
            }
        }

        log.info(
            "Drive full sync completed: "
                + "workspaceId={}, folderId={}, "
                + "processed={}, reconciled={}",
            workspaceId,
            folderId,
            saved,
            reconciled
        );

        // 5. 다음 증분 동기화에서 사용할 토큰 반환
        return new SyncResult(
            saved,
            startedAt,
            cursor
        );
    }

    // =====================================================
    // 5. 증분 동기화
    // =====================================================

    /**
     * Google Drive Changes API 기반 증분 동기화.
     *
     * 실행 순서:
     *
     * 1. 기존 cursor로 변경 내역 조회
     * 2. 현재 추적하는 파일 ID 확인
     * 3. 동일 파일의 중복 변경 내역 정리
     * 4. 추가·수정·삭제·이동 처리
     * 5. 새 cursor 반환
     *
     * 오류가 발생하면 새 cursor를 반환하지 않는다.
     * 따라서 상위 SyncService가 실패로 처리하고
     * 다음 시도에서 동일 변경을 다시 조회할 수 있다.
     */
    private SyncResult incrementalSync(
        Long workspaceId,
        String folderId,
        String token,
        String cursor,
        LocalDateTime startedAt
    ) {

        // 1. 변경 이력 조회
        DriveApiClient.ChangeResult result =
            driveApiClient.listChanges(
                token,
                cursor
            );

        Set<String> trackedIds =
            getTrackedFiles(
                workspaceId,
                folderId
            )
                .stream()
                .map(ContentRef::getOriginId)
                .collect(Collectors.toSet());

        // 3. 동일 파일의 여러 변경 내역 중
        // 마지막 변경 내역만 사용
        Map<String, DriveChange> latestChanges =
            new LinkedHashMap<>();

        for (DriveChange change : result.changes()) {

            if (change == null
                || change.fileId() == null
                || change.fileId().isBlank()) {

                throw new BusinessException(
                    ErrorCode.EXTERNAL_API_ERROR
                );
            }

            latestChanges.put(
                change.fileId(),
                change
            );
        }

        int saved = 0;
        int reconciled = 0;

        // 4. 변경 파일 처리
        for (DriveChange change :
            latestChanges.values()) {

            String fileId = change.fileId();

            DriveFile file = change.file();

            // -----------------------------------------
            // 4-1. 삭제되었거나 접근 권한을 잃은 파일
            // -----------------------------------------

            if (change.removed()) {

                if (trackedIds.contains(fileId)) {
                    reconcileLocation(
                        workspaceId, folderId, fileId, lookupFile(token, fileId)
                    );
                    reconciled++;
                }

                continue;
            }

            // -----------------------------------------
            // 4-2. 파일 정보가 누락된 경우
            // -----------------------------------------

            if (file == null) {

                // 응답을 임의로 무시하면 해당 변경이
                // 누락될 수 있으므로 동기화 실패 처리
                throw new BusinessException(
                    ErrorCode.EXTERNAL_API_ERROR
                );
            }

            // -----------------------------------------
            // 4-3. 선택한 폴더에 존재하는 파일
            // -----------------------------------------

            if (file.isDirectChildOf(folderId)) {

                // 신규 파일 생성
                // 기존 파일 수정
                // 휴지통 복원
                // 선택 폴더로 이동한 파일
                saveFile(
                    workspaceId,
                    folderId,
                    file,
                    startedAt
                );

                saved++;
                continue;
            }

            if (file.trashed() && file.isInFolder(folderId)) {
                contentRefService.updateDriveLocation(
                    workspaceId, fileId, null, folderId, DriveFileState.TRASHED
                );
                reconciled++;
            } else if (trackedIds.contains(fileId)) {
                reconcileLocation(workspaceId, folderId, fileId, file);
                reconciled++;
            }
        }

        log.info(
            "Drive incremental sync completed: "
                + "workspaceId={}, folderId={}, "
                + "updated={}, reconciled={}",
            workspaceId,
            folderId,
            saved,
            reconciled
        );

        // 5. 새로운 변경 토큰 반환
        return new SyncResult(
            saved,
            startedAt,
            result.newStartPageToken()
        );
    }

    // =====================================================
    // 6. 파일 메타데이터 저장
    // =====================================================

    /**
     * Google Drive 파일을 ContentRef에 저장한다.
     *
     * 파일 본문은 다운로드하지 않는다.
     *
     * 저장 정보:
     * - 원본 Google Drive ID
     * - 선택된 폴더 ID
     * - 파일명
     * - MIME 타입
     * - 설명
     * - Google Drive 링크
     * - 생성 시각
     * - 수정 시각
     *
     * ContentRefService.upsert()를 재사용하여
     * 기존 Task 등 ContentRef 참조 관계를 유지한다.
     */
    private void saveFile(
        Long workspaceId,
        String folderId,
        DriveFile file,
        LocalDateTime startedAt
    ) {

        if (file == null
            || file.id() == null
            || file.id().isBlank()) {

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }

        // 1. MIME 타입
        String mimeType =
            file.mimeType() != null
                ? file.mimeType()
                : "application/octet-stream";

        // 2. 파일 메타데이터 본문 구성
        StringBuilder content =
            new StringBuilder(
                "파일 유형: " + mimeType
            );

        String description =
            file.description();

        if (description != null
            && !description.isBlank()) {

            content.append("\n설명: ");
            content.append(description.trim());
        }

        // ContentRef.content DB 길이 초과 방지
        String storedContent =
            content.length() > MAX_CONTENT_LENGTH
                ? content.substring(
                0,
                MAX_CONTENT_LENGTH
            )
                : content.toString();

        // 3. 생성 및 수정 시각
        LocalDateTime createdAt =
            file.createdAt() != null
                ? file.createdAt()
                : file.modifiedAt() != null
                ? file.modifiedAt()
                : startedAt;

        LocalDateTime modifiedAt =
            file.modifiedAt() != null
                ? file.modifiedAt()
                : createdAt;

        // 4. 파일 이름
        String title =
            file.name() != null
                && !file.name().isBlank()
                ? file.name()
                : file.id();

        // 5. Google Drive 원본 링크
        String deepLink =
            file.webViewLink() != null
                && !file.webViewLink().isBlank()
                ? file.webViewLink()
                : "https://drive.google.com/file/d/"
                + file.id()
                + "/view";

        // 6. 공통 콘텐츠 저장 서비스 호출
        contentRefService.upsert(
            new ContentRefUpsert(
                workspaceId,
                null,
                PROVIDER,
                ContentType.FILE,
                file.id(),
                folderId,
                null,
                title,
                storedContent,
                deepLink,
                createdAt,
                modifiedAt,
                null,
                null
            )
        );
    }

    // =====================================================
    // 7. 선택된 폴더의 추적 파일 조회
    // =====================================================

    private List<ContentRef> getTrackedFiles(
        Long workspaceId,
        String folderId
    ) {

        return contentRefRepository
            .findAllByWorkspaceIdAndProviderAndChannelIdAndContentType(
                workspaceId,
                PROVIDER,
                folderId,
                ContentType.FILE
            );
    }

    // =====================================================
    // 8. 위치 및 원본 상태 반영
    // =====================================================

    private DriveFile lookupFile(String token, String fileId) {
        try {
            return driveApiClient.getFileMetadata(token, fileId);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                return null;
            }
            throw exception;
        }
    }

    private void reconcileLocation(Long workspaceId, String folderId, String fileId, DriveFile file) {
        if (file == null) {
            contentRefService.updateDriveLocation(
                workspaceId, fileId, folderId, folderId, DriveFileState.UNAVAILABLE
            );
            return;
        }

        String parentId = file.parents().contains(folderId)
            ? folderId
            : file.parents().stream().findFirst().orElse(null);
        DriveFileState state = file.trashed() ? DriveFileState.TRASHED : DriveFileState.AVAILABLE;
        contentRefService.updateDriveLocation(workspaceId, fileId, folderId, parentId, state);
    }
}
