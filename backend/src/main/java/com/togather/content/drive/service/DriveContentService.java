package com.togather.content.drive.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;

import com.togather.content.domain.ContentRef;
import com.togather.content.domain.ContentRef.DriveFileState;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveFile;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.service.TeamTokenResolver;

import com.togather.integration.service.IntegrationService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * Google Drive 콘텐츠 관리 서비스 (C-03).
 *
 * 기능:
 * 1. 연동된 폴더 목록 조회
 * 2. 파일 목록 조회
 * 3. 파일 상세 조회
 * 4. 휴지통 파일 목록 조회
 * 5. 최대 100MB 파일 업로드
 * 6. 파일 휴지통 이동
 * 7. 휴지통 파일 복원
 * 8. 파일 다운로드
 * 9. PDF 및 이미지 미리보기
 * 10. 콘텐츠 변경 시 SSE 이벤트 전송
 *
 * Google Drive 원본과 PostgreSQL ContentRef를
 * 함께 관리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveContentService {

    private static final Provider PROVIDER =
        Provider.GOOGLE_DRIVE;

    private static final long MAX_UPLOAD_SIZE =
        DriveApiClient.MAX_UPLOAD_SIZE;

    private final ContentRefRepository contentRefRepository;
    private final ContentRefService contentRefService;
    private final IntegrationService integrationService;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final DriveApiClient driveApiClient;
    private final TeamTokenResolver teamTokenResolver;
    private final WorkspaceEventService workspaceEventService;

    /**
     * 1. 연동된 Google Drive 폴더 목록.
     *
     * 파일 업로드 화면에서 폴더를 선택할 때 사용한다.
     */
    public List<String> getFolders(
        Long workspaceId,
        Long userId
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        return integrationService.getConnectionTargets(
                workspaceId,
                PROVIDER
            )
            .stream()
            .distinct()
            .toList();
    }

    /**
     * 2. Google Drive 파일 목록 조회.
     *
     * folderId가 없으면 연동된 모든 폴더 조회.
     * folderId가 있으면 해당 폴더만 조회.
     *
     * 원본에서 삭제된 파일은 제외한다.
     */
    public List<ContentRefDTO> getFiles(
        Long workspaceId,
        Long userId,
        String folderId
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        List<String> selectedFolders =
            getSelectedFolders(workspaceId);

        if (selectedFolders.isEmpty()) {
            return List.of();
        }

        List<String> folders;

        if (folderId == null || folderId.isBlank()) {

            folders = selectedFolders;

        } else {

            if (!selectedFolders.contains(folderId)) {
                throw new BusinessException(
                    ErrorCode.INTEGRATION_TARGET_NOT_SELECTED
                );
            }

            folders = List.of(folderId);
        }

        List<ContentRef> files =
            folders.stream()
                .distinct()
                .flatMap(id ->
                    contentRefRepository
                        .findAllByWorkspaceIdAndProviderAndChannelIdAndIsOriginDeletedFalse(
                            workspaceId,
                            PROVIDER,
                            id
                        )
                        .stream()
                )
                .filter(
                    ref -> ref.getContentType()
                        == ContentType.FILE
                )
                .sorted(
                    Comparator.comparing(
                        ContentRef::getLastModifiedAt,
                        Comparator.nullsFirst(
                            Comparator.naturalOrder()
                        )
                    ).reversed()
                )
                .toList();

        return contentRefService.toDtos(files);
    }

    /**
     * 3. 파일 상세 조회.
     */
    public ContentRefDTO getFile(
        Long workspaceId,
        Long userId,
        Long contentRefId
    ) {

        ContentRef file = requireFile(
            workspaceId,
            userId,
            contentRefId,
            false
        );

        return contentRefService.getContentRef(
            file.getContentRefId()
        );
    }

    /**
     * 4. 휴지통 파일 목록 조회.
     *
     * ContentRef에 저장된 파일 중
     * Drive 휴지통에 있는 것으로 확인된 항목을 조회한다.
     *
     * 이 목록은 실제 Google Drive 휴지통 전체가 아니라
     * ToGather에서 이전에 수집한 파일을 기준으로 한다.
     */
    public List<ContentRefDTO> getTrashFiles(
        Long workspaceId,
        Long userId
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        List<String> selectedFolders =
            getSelectedFolders(workspaceId);

        if (selectedFolders.isEmpty()) {
            return List.of();
        }

        List<ContentRef> deletedFiles =
            contentRefRepository
                .findAllByWorkspaceIdAndProviderAndParentRefIdIsNullOrderByLastModifiedAtDesc(
                    workspaceId,
                    PROVIDER
                )
                .stream()
                .filter(ContentRef::isDriveTrashed)
                .filter(
                    ref -> ref.getContentType()
                        == ContentType.FILE
                )
                .filter(
                    ref -> selectedFolders.contains(
                        ref.getChannelId()
                    )
                )
                .toList();

        return contentRefService.toDtos(
            deletedFiles
        );
    }

    /**
     * 5. 기존 byte[] 업로드 호환 메서드.
     *
     * 기존 코드에서 byte[]를 전달할 수 있도록 유지한다.
     *
     * 실제 프론트엔드 Controller는
     * 아래 Path 방식 사용을 권장한다.
     */
    public ContentRefDTO uploadFile(
        Long workspaceId,
        Long userId,
        String folderId,
        String fileName,
        String mimeType,
        byte[] fileBytes
    ) {

        validateFileName(fileName);

        if (fileBytes == null
            || fileBytes.length == 0
            || fileBytes.length > MAX_UPLOAD_SIZE) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        // 업로드 권한 검증
        String token = resolveToken(
            workspaceId,
            userId,
            folderId
        );

        DriveFile uploaded = driveApiClient.uploadFile(
            token,
            folderId,
            fileName,
            mimeType,
            fileBytes
        );

        ContentRefDTO result = saveUploadedFile(
            workspaceId,
            folderId,
            fileName,
            uploaded
        );

        publishDriveChanged(
            workspaceId,
            folderId
        );

        return result;
    }

    /**
     * 6. Google Drive 파일 업로드.
     *
     * 최대 100MB.
     *
     * 파일을 전체 byte[]로 변환하지 않고
     * Path를 DriveApiClient에 전달한다.
     *
     * DriveApiClient가 4MB 단위로 분할 전송한다.
     */
    public ContentRefDTO uploadFile(
        Long workspaceId,
        Long userId,
        String folderId,
        String fileName,
        String mimeType,
        Path localFile
    ) {

        validateFileName(fileName);

        if (localFile == null
            || !Files.isRegularFile(localFile)) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        long fileSize;

        try {

            fileSize = Files.size(localFile);

        } catch (IOException e) {

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }

        if (fileSize == 0
            || fileSize > MAX_UPLOAD_SIZE) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        // 워크스페이스 및 폴더 권한 확인
        String token = resolveToken(
            workspaceId,
            userId,
            folderId
        );

        // Google Drive에 실제 업로드
        DriveFile uploaded = driveApiClient.uploadFile(
            token,
            folderId,
            fileName,
            mimeType,
            localFile
        );

        // PostgreSQL ContentRef 저장
        ContentRefDTO result = saveUploadedFile(
            workspaceId,
            folderId,
            fileName,
            uploaded
        );

        // 프론트엔드 변경 알림
        publishDriveChanged(
            workspaceId,
            folderId
        );

        log.info(
            "Drive upload completed: workspaceId={}, fileId={}",
            workspaceId,
            uploaded.id()
        );

        return result;
    }

    /**
     * 7. Google Drive 파일 삭제.
     *
     * 실제 영구 삭제하지 않고 휴지통으로 이동한다.
     *
     * 순서:
     * 1. 사용자 및 파일 권한 검증
     * 2. Google Drive 원본 조회
     * 3. 선택된 폴더의 파일인지 검증
     * 4. Drive 휴지통으로 이동
     * 5. ContentRef 삭제 표시
     * 6. SSE 이벤트 발행
     */
    public void trashFile(
        Long workspaceId,
        Long userId,
        Long contentRefId
    ) {

        ContentRef file = requireFile(
            workspaceId,
            userId,
            contentRefId,
            false
        );

        String folderId = file.getChannelId();

        String token = resolveToken(
            workspaceId,
            userId,
            folderId
        );

        DriveFile remote = driveApiClient.getFileMetadata(
            token,
            file.getOriginId()
        );

        // 실제 선택된 폴더 안의 파일인지 확인
        if (!remote.isDirectChildOf(folderId)) {

            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        // Google Drive 원본 휴지통 이동
        driveApiClient.trashFile(
            token,
            file.getOriginId()
        );

        // PostgreSQL에 원본 삭제 상태 반영
        contentRefService.updateDriveLocation(
            workspaceId,
            file.getOriginId(),
            folderId,
            folderId,
            DriveFileState.TRASHED
        );

        // 화면 자동 갱신 이벤트
        publishDriveChanged(
            workspaceId,
            folderId
        );

        log.info(
            "Drive file trashed: workspaceId={}, contentRefId={}",
            workspaceId,
            contentRefId
        );
    }

    /**
     * 8. Google Drive 파일 복원.
     *
     * 이전에 ToGather에서 수집했던 파일만 복원한다.
     *
     * 복원 후 ContentRefService.upsert()로
     * 최신 메타데이터를 반영한다.
     */
    public ContentRefDTO restoreFile(
        Long workspaceId,
        Long userId,
        Long contentRefId
    ) {

        ContentRef file = requireFile(
            workspaceId,
            userId,
            contentRefId,
            true
        );

        String folderId = file.getChannelId();

        String token = resolveToken(
            workspaceId,
            userId,
            folderId
        );

        DriveFile remote = driveApiClient.getFileMetadata(
            token,
            file.getOriginId()
        );

        // 현재 원본이 다른 폴더로 이동된 경우
        if (!remote.parents().contains(folderId)) {

            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        DriveFile restored;

        if (remote.trashed()) {

            // 실제 Google Drive 파일 복원
            restored = driveApiClient.restoreFile(
                token,
                file.getOriginId()
            );

        } else {

            // Google Drive에서 이미 복원된 상태
            restored = remote;
        }

        // PostgreSQL 메타데이터 갱신
        ContentRefDTO result = saveUploadedFile(
            workspaceId,
            folderId,
            restored.name(),
            restored
        );

        publishDriveChanged(
            workspaceId,
            folderId
        );

        log.info(
            "Drive file restored: workspaceId={}, contentRefId={}",
            workspaceId,
            contentRefId
        );

        return result;
    }

    /**
     * 9. 다운로드 및 미리보기 준비.
     *
     * 사용자가 접근 가능한 파일인지 검증하고
     * Google Drive 원본의 MIME 타입을 확인한다.
     *
     * 반환하는 DriveTransfer에는 액세스 토큰이
     * 포함되므로 브라우저에 JSON으로 반환하지 않는다.
     */
    public DriveTransfer prepareTransfer(
        Long workspaceId,
        Long userId,
        Long contentRefId,
        boolean preview
    ) {

        ContentRef file = requireFile(
            workspaceId,
            userId,
            contentRefId,
            false
        );

        String folderId = file.getChannelId();

        String token = resolveToken(
            workspaceId,
            userId,
            folderId
        );

        DriveFile remote = driveApiClient.getFileMetadata(
            token,
            file.getOriginId()
        );

        // 선택한 폴더의 살아있는 파일인지 확인
        if (!remote.isDirectChildOf(folderId)) {

            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        String originalMime =
            remote.mimeType() != null
                ? remote.mimeType()
                : "application/octet-stream";

        boolean googleNative =
            originalMime.startsWith(
                "application/vnd.google-apps."
            );

        boolean exportPdf =
            DriveApiClient.isPdfExportable(
                originalMime
            );

        // 다운로드를 지원하지 않는 Google 고유 형식
        if (googleNative && !exportPdf) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        // Google 문서 형식은 PDF로 내보내기
        String servedMime =
            exportPdf
                ? "application/pdf"
                : originalMime;

        // 브라우저에 직접 표시해도 되는 형식만 허용
        boolean safePreview =
            servedMime.equals("application/pdf")
                || servedMime.equals("text/plain")
                || servedMime.equals("image/png")
                || servedMime.equals("image/jpeg")
                || servedMime.equals("image/gif")
                || servedMime.equals("image/webp");

        if (preview && !safePreview) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        String name = remote.name() != null
            ? remote.name()
            : file.getOriginId();

        if (exportPdf) {
            name += ".pdf";
        }

        // 응답 헤더에서 안전하게 사용할 파일명
        name = name.replace('\\', '/');

        name = name.substring(
            name.lastIndexOf('/') + 1
        );

        name = name.replace("\r", "")
            .replace("\n", "");

        return new DriveTransfer(
            token,
            remote.id(),
            name,
            servedMime,
            originalMime
        );
    }

    /**
     * 10. Google Drive 파일 데이터 전송.
     *
     * 실제 파일 데이터는 클라이언트로 스트리밍한다.
     * 전체 파일을 서버 메모리에 적재하지 않는다.
     */
    public void transfer(
        DriveTransfer transfer,
        OutputStream output
    ) throws IOException {

        driveApiClient.streamFile(
            transfer.accessToken(),
            transfer.fileId(),
            transfer.originalMimeType(),
            output
        );
    }

    /**
     * Controller 내부에서만 사용하는 전송 정보.
     *
     * accessToken은 외부 응답에 노출하면 안 된다.
     */
    public record DriveTransfer(
        String accessToken,
        String fileId,
        String fileName,
        String mimeType,
        String originalMimeType
    ) {}

    /**
     * 11. 파일 접근 권한 확인.
     *
     * allowDeleted = false:
     * 삭제되지 않은 파일만 허용
     *
     * allowDeleted = true:
     * 삭제 표시된 파일도 접근 허용
     */
    private ContentRef requireFile(
        Long workspaceId,
        Long userId,
        Long contentRefId,
        boolean allowDeleted
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        ContentRef file = contentRefRepository
            .findById(contentRefId)
            .orElseThrow(() ->
                new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND
                )
            );

        if (!workspaceId.equals(file.getWorkspaceId())
            || file.getProvider() != PROVIDER
            || file.getContentType() != ContentType.FILE
            || (!allowDeleted && file.isOriginDeleted())) {

            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        if (!getSelectedFolders(workspaceId)
            .contains(file.getChannelId())) {

            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        return file;
    }

    /**
     * 12. Google Drive 토큰 획득.
     *
     * 연동 관리에서 선택된 폴더에 대한
     * 액세스 토큰만 사용한다.
     */
    private String resolveToken(
        Long workspaceId,
        Long userId,
        String folderId
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        if (folderId == null
            || folderId.isBlank()
            || !getSelectedFolders(workspaceId)
            .contains(folderId)) {

            throw new BusinessException(
                ErrorCode.INTEGRATION_TARGET_NOT_SELECTED
            );
        }

        return teamTokenResolver.tokenFor(
            workspaceId,
            PROVIDER,
            folderId,
            userId
        );
    }

    /**
     * 13. 연동 관리에서 선택한 Drive 폴더 조회.
     */
    private List<String> getSelectedFolders(
        Long workspaceId
    ) {

        return integrationService.getConnectionTargets(
            workspaceId,
            PROVIDER
        );
    }

    /**
     * 14. 파일명 검증.
     */
    private static void validateFileName(
        String fileName
    ) {

        if (fileName == null
            || fileName.isBlank()
            || fileName.length() > 255
            || fileName.indexOf('\0') >= 0) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }
    }

    /**
     * 15. 업로드·복원된 파일의 메타데이터 저장.
     */
    private ContentRefDTO saveUploadedFile(
        Long workspaceId,
        String folderId,
        String requestedName,
        DriveFile uploaded
    ) {

        if (uploaded == null
            || uploaded.id() == null
            || uploaded.id().isBlank()) {

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }

        LocalDateTime now =
            LocalDateTime.now(ZoneOffset.UTC);

        LocalDateTime createdAt =
            uploaded.createdAt() != null
                ? uploaded.createdAt()
                : now;

        LocalDateTime modifiedAt =
            uploaded.modifiedAt() != null
                ? uploaded.modifiedAt()
                : createdAt;

        String mime =
            uploaded.mimeType() != null
                ? uploaded.mimeType()
                : "application/octet-stream";

        String content =
            "파일 유형: " + mime;

        if (uploaded.description() != null
            && !uploaded.description().isBlank()) {

            content += "\n설명: "
                + uploaded.description().trim();
        }

        // ContentRef의 DB 문자열 길이 초과 방지
        if (content.length() > 240) {
            content = content.substring(0, 240);
        }

        String link =
            uploaded.webViewLink() != null
                ? uploaded.webViewLink()
                : "https://drive.google.com/file/d/"
                + uploaded.id() + "/view";

        return contentRefService.upsert(
            new ContentRefUpsert(
                workspaceId,
                null,
                PROVIDER,
                ContentType.FILE,
                uploaded.id(),
                folderId,
                null,
                uploaded.name() != null
                    ? uploaded.name()
                    : requestedName,
                content,
                link,
                createdAt,
                modifiedAt,
                null,
                null
            )
        );
    }

    /**
     * 16. Drive 변경 이벤트 전송.
     *
     * 기존 ToGather WorkspaceEventService를 재사용한다.
     */
    private void publishDriveChanged(
        Long workspaceId,
        String folderId
    ) {

        workspaceEventService.publish(
            workspaceId,
            PROVIDER.name(),
            folderId
        );
    }
}
