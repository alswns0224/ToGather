package com.togather.content.drive.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.common.response.ApiResponse;

import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.service.DriveContentService;
import com.togather.content.drive.service.DriveContentService.DriveTransfer;
import com.togather.content.dto.ContentRefDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Google Drive 콘텐츠 관리 API (C-03).
 *
 * 지원 기능:
 *
 * 1. 연동 폴더 조회
 * 2. 파일 목록 조회
 * 3. 파일 상세 조회
 * 4. 휴지통 파일 목록 조회
 * 5. 파일 업로드 (최대 100MB)
 * 6. 파일 삭제 (Google Drive 휴지통 이동)
 * 7. 삭제한 파일 복원
 * 8. 파일 다운로드
 * 9. 파일 미리보기
 *
 * 로그인 사용자 식별에는
 * ToGather의 @LoginUser를 사용한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/drive")
public class DriveContentController {

    private static final long MAX_UPLOAD_SIZE =
        DriveApiClient.MAX_UPLOAD_SIZE;

    private final DriveContentService driveContentService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    /**
     * 1. Google Drive 연동 폴더 조회.
     *
     * GET /api/workspaces/{workspaceId}/drive/folders
     *
     * 업로드 화면에서 사용자가
     * 저장할 폴더를 선택할 때 사용한다.
     */
    @GetMapping("/folders")
    public ApiResponse<List<String>> getFolders(
        @LoginUser Long userId,
        @PathVariable Long workspaceId
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        return ApiResponse.ok(
            driveContentService.getFolders(
                workspaceId,
                userId
            )
        );
    }

    /**
     * 2. Google Drive 파일 목록 조회.
     *
     * GET /api/workspaces/{workspaceId}/drive/files
     *
     * folderId 생략:
     * 연동된 모든 폴더의 파일 조회
     *
     * folderId 지정:
     * 해당 폴더의 파일만 조회
     */
    @GetMapping("/files")
    public ApiResponse<List<ContentRefDTO>> getFiles(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @RequestParam(required = false) String folderId
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        return ApiResponse.ok(
            driveContentService.getFiles(
                workspaceId,
                userId,
                folderId
            )
        );
    }

    /**
     * 3. Google Drive 파일 상세 조회.
     *
     * GET /api/workspaces/{workspaceId}/drive/files/{contentRefId}
     */
    @GetMapping("/files/{contentRefId}")
    public ApiResponse<ContentRefDTO> getFile(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long contentRefId
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        return ApiResponse.ok(
            driveContentService.getFile(
                workspaceId,
                userId,
                contentRefId
            )
        );
    }

    /**
     * 4. Google Drive 휴지통 파일 목록 조회.
     *
     * GET /api/workspaces/{workspaceId}/drive/trash
     *
     * 이전에 ToGather에서 수집한 파일 중
     * 삭제 상태인 파일을 조회한다.
     */
    @GetMapping("/trash")
    public ApiResponse<List<ContentRefDTO>> getTrashFiles(
        @LoginUser Long userId,
        @PathVariable Long workspaceId
    ) {

        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        return ApiResponse.ok(
            driveContentService.getTrashFiles(
                workspaceId,
                userId
            )
        );
    }

    /**
     * 5. Google Drive 파일 직접 업로드.
     *
     * POST /api/workspaces/{workspaceId}/drive/files
     *
     * Content-Type: multipart/form-data
     *
     * 요청 필드:
     * folderId : 업로드할 Drive 폴더 ID
     * file     : 업로드 파일
     *
     * 최대 크기: 100MB
     *
     * 업로드 파일을 임시 파일로 저장하고
     * Path 기반으로 Service에 전달한다.
     */
    @PostMapping(
        value = "/files",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContentRefDTO> uploadFile(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @RequestParam("folderId") String folderId,
        @RequestPart("file") MultipartFile file
    ) {

        // 1. 워크스페이스 권한 검증
        workspaceAccessChecker.checkMember(
            workspaceId,
            userId
        );

        // 2. 파일명 확인
        String fileName = file.getOriginalFilename();

        // 3. 파일 유효성 검사
        if (file.isEmpty()
            || fileName == null
            || fileName.isBlank()
            || fileName.length() > 255
            || fileName.indexOf('\0') >= 0
            || file.getSize() > MAX_UPLOAD_SIZE) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        // 4. 임시 파일 생성 및 업로드
        Path tempFile = null;

        try {

            tempFile = Files.createTempFile(
                "togather-drive-upload-",
                ".tmp"
            );

            // MultipartFile을 서버 임시 파일에 저장
            // 전체 파일을 byte[]로 변환하지 않음
            file.transferTo(tempFile);

            // 실제 저장된 파일 크기 재검증
            long actualSize = Files.size(tempFile);

            if (actualSize == 0
                || actualSize > MAX_UPLOAD_SIZE) {

                throw new BusinessException(
                    ErrorCode.INVALID_INPUT
                );
            }

            // 5. Google Drive 업로드 및 DB 반영
            ContentRefDTO uploaded =
                driveContentService.uploadFile(
                    workspaceId,
                    userId,
                    folderId,
                    fileName,
                    file.getContentType(),
                    tempFile
                );

            log.info(
                "Drive file uploaded: "
                    + "workspaceId={}, contentRefId={}",
                workspaceId,
                uploaded.contentRefId()
            );

            // 6. 성공 응답
            return ApiResponse.ok(uploaded);

        } catch (IOException e) {

            log.warn(
                "Drive upload temporary file error: {}",
                e.getClass().getSimpleName()
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );

        } finally {

            // 업로드 성공·실패와 관계없이
            // 서버 임시 파일 정리
            if (tempFile != null) {

                try {

                    Files.deleteIfExists(tempFile);

                } catch (IOException e) {

                    log.warn(
                        "Drive temporary file cleanup failed: {}",
                        e.getClass().getSimpleName()
                    );
                }
            }
        }
    }

    /**
     * 6. Google Drive 파일 삭제.
     *
     * DELETE /api/workspaces/{workspaceId}/drive/files/{contentRefId}
     *
     * 원본 파일을 영구 삭제하지 않고
     * Google Drive 휴지통으로 이동한다.
     *
     * 성공 시 204 No Content 반환.
     */
    @DeleteMapping("/files/{contentRefId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trashFile(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long contentRefId
    ) {

        driveContentService.trashFile(
            workspaceId,
            userId,
            contentRefId
        );

        log.info(
            "Drive file moved to trash: "
                + "workspaceId={}, contentRefId={}",
            workspaceId,
            contentRefId
        );
    }

    /**
     * 7. Google Drive 파일 복원.
     *
     * POST /api/workspaces/{workspaceId}/drive/files/{contentRefId}/restore
     *
     * Google Drive 휴지통에서 파일을 복원하고
     * ContentRef 정보를 갱신한다.
     */
    @PostMapping("/files/{contentRefId}/restore")
    public ApiResponse<ContentRefDTO> restoreFile(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long contentRefId
    ) {

        ContentRefDTO restored =
            driveContentService.restoreFile(
                workspaceId,
                userId,
                contentRefId
            );

        log.info(
            "Drive file restored: "
                + "workspaceId={}, contentRefId={}",
            workspaceId,
            contentRefId
        );

        return ApiResponse.ok(restored);
    }

    /**
     * 8. Google Drive 파일 다운로드.
     *
     * GET /api/workspaces/{workspaceId}/drive/files/{contentRefId}/download
     *
     * Content-Disposition: attachment
     *
     * 일반 파일은 원본 파일로 다운로드.
     * Google Docs 등은 PDF로 내보내기.
     *
     * 파일 전체를 서버 메모리에 적재하지 않고
     * StreamingResponseBody로 전달한다.
     */
    @GetMapping("/files/{contentRefId}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long contentRefId
    ) {

        return buildTransferResponse(
            workspaceId,
            userId,
            contentRefId,
            false
        );
    }

    /**
     * 9. Google Drive 파일 미리보기.
     *
     * GET /api/workspaces/{workspaceId}/drive/files/{contentRefId}/preview
     *
     * Content-Disposition: inline
     *
     * 지원 형식:
     * - PDF
     * - PNG, JPG, GIF, WEBP
     * - TXT
     * - Google Docs / Sheets / Slides PDF 변환
     *
     * 지원하지 않는 형식은 Service에서 거부한다.
     */
    @GetMapping("/files/{contentRefId}/preview")
    public ResponseEntity<StreamingResponseBody> previewFile(
        @LoginUser Long userId,
        @PathVariable Long workspaceId,
        @PathVariable Long contentRefId
    ) {

        return buildTransferResponse(
            workspaceId,
            userId,
            contentRefId,
            true
        );
    }

    /**
     * 10. 다운로드 및 미리보기 공통 응답 생성.
     *
     * 파일 접근 권한과 MIME 타입은
     * DriveContentService에서 먼저 검증한다.
     */
    private ResponseEntity<StreamingResponseBody>
    buildTransferResponse(
        Long workspaceId,
        Long userId,
        Long contentRefId,
        boolean preview
    ) {

        // 1. 사용자 권한 및 파일 정보 확인
        DriveTransfer transfer =
            driveContentService.prepareTransfer(
                workspaceId,
                userId,
                contentRefId,
                preview
            );

        // 2. Content-Disposition 구성
        ContentDisposition disposition =
            (preview
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(
                    transfer.fileName(),
                    StandardCharsets.UTF_8
                )
                .build();

        // 3. 스트리밍 실행
        StreamingResponseBody streamingBody =
            outputStream -> {

                driveContentService.transfer(
                    transfer,
                    outputStream
                );

                outputStream.flush();
            };

        // 4. HTTP 응답
        return ResponseEntity.ok()
            .contentType(
                MediaType.parseMediaType(
                    transfer.mimeType()
                )
            )
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                disposition.toString()
            )
            .header(
                "X-Content-Type-Options",
                "nosniff"
            )
            .cacheControl(
                CacheControl.noStore()
            )
            .body(streamingBody);
    }
}
