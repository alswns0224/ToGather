package com.togather.content.drive.client;

import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google Drive API Client (C-03).
 *
 * 기능:
 * 1. 변경 이력 시작 토큰 조회
 * 2. 선택 폴더의 파일 조회
 * 3. 휴지통 파일 조회
 * 4. 증분 동기화
 * 5. 최대 100MB 파일 업로드
 * 6. 업로드 중단 시 상태 조회
 * 7. 파일 메타데이터 조회
 * 8. 파일 휴지통 이동
 * 9. 파일 복원
 * 10. 파일 다운로드 및 PDF 내보내기
 *
 * 파일 업로드는 Resumable Upload를 사용한다.
 */
@Slf4j
@Component
public class DriveApiClient {

    private static final String BASE =
        "https://www.googleapis.com/drive/v3";

    private static final String UPLOAD_URL =
        "https://www.googleapis.com/upload/drive/v3/files";

    private static final String FILE_FIELDS =
        "id,name,mimeType,description,webViewLink,"
            + "createdTime,modifiedTime,parents,trashed";

    private static final int MAX_PAGES = 200;

    // 최대 업로드 크기: 100MB
    public static final long MAX_UPLOAD_SIZE =
        100L * 1024 * 1024;

    // 4MB 청크 (256KB의 배수)
    private static final int CHUNK_SIZE =
        4 * 1024 * 1024;

    private static final int MAX_STALLED_ATTEMPTS = 3;

    // 업로드 완료 응답에서 Google Drive 파일 ID 추출
    private static final Pattern FILE_ID_IN_JSON =
        Pattern.compile(
            "\"id\"\\s*:\\s*\"([A-Za-z0-9_-]+)\""
        );

    // 업로드 진행 상태의 Range 헤더
    private static final Pattern RECEIVED_RANGE =
        Pattern.compile("bytes=0-(\\d+)");

    /**
     * 업로드 전용 HTTP 클라이언트.
     *
     * Google Drive는 업로드 중 308 응답을 보낸다.
     * 일반적인 HTTP 리다이렉트로 처리하지 않도록
     * 자동 리다이렉트를 비활성화한다.
     */
    private static final HttpClient UPLOAD_HTTP =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(
                HttpClient.Redirect.NEVER
            )
            .build();

    private static final ParameterizedTypeReference<
        Map<String, Object>> JSON =
        new ParameterizedTypeReference<>() {};

    private final RestClient externalRestClient;
    private final RestClient transferRestClient;

    public DriveApiClient(
        RestClient externalRestClient,
        @Value("${togather.drive.transfer-timeout}") Duration transferTimeout
    ) {
        this.externalRestClient = externalRestClient;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(UPLOAD_HTTP);
        factory.setReadTimeout(transferTimeout);
        this.transferRestClient = externalRestClient.mutate().requestFactory(factory).build();
    }

    /**
     * Google Drive 파일 메타데이터.
     */
    public record DriveFile(
        String id,
        String name,
        String mimeType,
        String description,
        String webViewLink,
        LocalDateTime createdAt,
        LocalDateTime modifiedAt,
        List<String> parents,
        boolean trashed
    ) {
        public boolean isDirectChildOf(String folderId) {
            return !trashed && isInFolder(folderId);
        }

        public boolean isInFolder(String folderId) {
            return !"application/vnd.google-apps.folder"
                .equals(mimeType)
                && parents.contains(folderId);
        }
    }

    /**
     * 변경된 파일 정보.
     */
    public record DriveChange(
        String fileId,
        boolean removed,
        DriveFile file
    ) {}

    /**
     * 증분 동기화 결과.
     */
    public record ChangeResult(
        List<DriveChange> changes,
        String newStartPageToken
    ) {}

    /**
     * 업로드 진행 상태.
     */
    private record UploadProgress(
        long nextOffset,
        String completedId
    ) {}

    // =====================================================
    // 1. 변경 이력 시작 토큰 조회
    // =====================================================

    public String getStartPageToken(
        String accessToken
    ) {

        UriComponentsBuilder uri =
            UriComponentsBuilder.fromUriString(
                    BASE + "/changes/startPageToken"
                )
                .queryParam("supportsAllDrives", true);

        Map<String, Object> body =
            request(accessToken, uri);

        String token = str(body, "startPageToken");

        if (token == null || token.isBlank()) {
            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }

        return token;
    }

    // =====================================================
    // 2. 선택한 폴더의 파일 목록 조회
    // =====================================================

    public List<DriveFile> listFolderFiles(
        String accessToken,
        String folderId
    ) {
        return listFolderFilesByTrashState(
            accessToken,
            folderId,
            false
        );
    }

    // =====================================================
    // 3. 선택 폴더의 휴지통 파일 목록 조회
    // =====================================================

    public List<DriveFile> listTrashedFolderFiles(
        String accessToken,
        String folderId
    ) {
        return listFolderFilesByTrashState(
            accessToken,
            folderId,
            true
        );
    }

    private List<DriveFile> listFolderFilesByTrashState(
        String accessToken,
        String folderId,
        boolean trashed
    ) {

        validateId(folderId);

        List<DriveFile> files = new ArrayList<>();
        String pageToken = null;

        for (int page = 0; page < MAX_PAGES; page++) {

            UriComponentsBuilder uri =
                UriComponentsBuilder.fromUriString(
                        BASE + "/files"
                    )
                    .queryParam(
                        "q",
                        "'" + folderId
                            + "' in parents and trashed = "
                            + trashed
                    )
                    .queryParam(
                        "fields",
                        "nextPageToken,incompleteSearch,files("
                            + FILE_FIELDS + ")"
                    )
                    .queryParam("pageSize", 100)
                    .queryParam("supportsAllDrives", true)
                    .queryParam(
                        "includeItemsFromAllDrives",
                        true
                    );

            if (pageToken != null) {
                uri.queryParam(
                    "pageToken",
                    pageToken
                );
            }

            Map<String, Object> body =
                request(accessToken, uri);

            if (Boolean.TRUE.equals(
                body.get("incompleteSearch"))) {

                throw new BusinessException(
                    ErrorCode.EXTERNAL_API_ERROR
                );
            }

            for (Map<String, Object> item :
                list(body, "files")) {

                DriveFile file = toFile(item);

                if (file != null
                    && file.trashed() == trashed
                    && file.parents().contains(folderId)
                    && !"application/vnd.google-apps.folder"
                    .equals(file.mimeType())) {

                    files.add(file);
                }
            }

            pageToken = str(
                body,
                "nextPageToken"
            );

            if (pageToken == null
                || pageToken.isBlank()) {

                return List.copyOf(files);
            }
        }

        throw new BusinessException(
            ErrorCode.EXTERNAL_API_ERROR
        );
    }

    // =====================================================
    // 4. Google Drive 증분 동기화
    // =====================================================

    public ChangeResult listChanges(
        String accessToken,
        String startPageToken
    ) {

        if (startPageToken == null
            || startPageToken.isBlank()) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        List<DriveChange> changes =
            new ArrayList<>();

        String pageToken = startPageToken;

        for (int page = 0; page < MAX_PAGES; page++) {

            UriComponentsBuilder uri =
                UriComponentsBuilder.fromUriString(
                        BASE + "/changes"
                    )
                    .queryParam("pageToken", pageToken)
                    .queryParam("pageSize", 100)
                    .queryParam("includeRemoved", true)
                    .queryParam("supportsAllDrives", true)
                    .queryParam(
                        "includeItemsFromAllDrives",
                        true
                    )
                    .queryParam(
                        "fields",
                        "nextPageToken,newStartPageToken,"
                            + "changes(fileId,removed,file("
                            + FILE_FIELDS + "))"
                    );

            Map<String, Object> body =
                request(accessToken, uri);

            for (Map<String, Object> item :
                list(body, "changes")) {

                String fileId =
                    str(item, "fileId");

                if (fileId == null) {
                    continue;
                }

                Map<String, Object> fileMap =
                    nestedMap(item.get("file"));

                changes.add(
                    new DriveChange(
                        fileId,
                        Boolean.TRUE.equals(
                            item.get("removed")
                        ),
                        fileMap == null
                            ? null
                            : toFile(fileMap)
                    )
                );
            }

            String nextPage =
                str(body, "nextPageToken");

            if (nextPage != null
                && !nextPage.isBlank()) {

                pageToken = nextPage;
                continue;
            }

            String newToken =
                str(body, "newStartPageToken");

            if (newToken == null
                || newToken.isBlank()) {

                throw new BusinessException(
                    ErrorCode.EXTERNAL_API_ERROR
                );
            }

            return new ChangeResult(
                List.copyOf(changes),
                newToken
            );
        }

        throw new BusinessException(
            ErrorCode.EXTERNAL_API_ERROR
        );
    }

    // =====================================================
    // 5. 기존 byte[] 업로드 호환 메서드
    // =====================================================

    /**
     * 기존 Service 코드와의 호환성을 위해 유지.
     *
     * 새로운 Controller에서는 Path 방식 사용을 권장.
     */
    public DriveFile uploadFile(
        String accessToken,
        String folderId,
        String name,
        String mimeType,
        byte[] bytes
    ) {

        if (bytes == null
            || bytes.length == 0
            || bytes.length > MAX_UPLOAD_SIZE) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        Path temp = null;

        try {

            temp = Files.createTempFile(
                "togather-drive-client-",
                ".tmp"
            );

            Files.write(temp, bytes);

            return uploadFile(
                accessToken,
                folderId,
                name,
                mimeType,
                temp
            );

        } catch (IOException e) {

            log.warn(
                "Drive temporary upload file error: {}",
                e.getClass().getSimpleName()
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );

        } finally {

            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn(
                        "Temp file cleanup failed"
                    );
                }
            }
        }
    }

    // =====================================================
    // 6. 최대 100MB Resumable Upload
    // =====================================================

    /**
     * 업로드 순서:
     *
     * 1. 파일 검증
     * 2. Google Drive 업로드 세션 생성
     * 3. 4MB 단위 전송
     * 4. 308 응답에서 수신 위치 확인
     * 5. 전송 오류 시 진행 상태 재조회
     * 6. 업로드 완료 파일 메타데이터 반환
     */
    public DriveFile uploadFile(
        String accessToken,
        String folderId,
        String name,
        String mimeType,
        Path localFile
    ) {

        validateId(folderId);

        String safeName =
            safeFileName(name);

        String safeMime =
            safeMimeType(mimeType);

        if (accessToken == null
            || accessToken.isBlank()
            || localFile == null) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        try {

            long size = Files.size(localFile);

            if (size == 0
                || size > MAX_UPLOAD_SIZE
                || !Files.isRegularFile(localFile)) {

                throw new BusinessException(
                    ErrorCode.INVALID_INPUT
                );
            }

            URI session = startUpload(
                accessToken,
                folderId,
                safeName,
                safeMime,
                size
            );

            long offset = 0;
            int stalled = 0;

            try (RandomAccessFile input =
                     new RandomAccessFile(
                         localFile.toFile(),
                         "r"
                     )) {

                while (offset < size) {

                    int length = (int) Math.min(
                        CHUNK_SIZE,
                        size - offset
                    );

                    byte[] chunk = new byte[length];

                    input.seek(offset);
                    input.readFully(chunk);

                    long end = offset + length;

                    HttpRequest upload =
                        HttpRequest.newBuilder(session)
                            .timeout(
                                Duration.ofMinutes(2)
                            )
                            .header(
                                "Authorization",
                                "Bearer " + accessToken
                            )
                            .header(
                                "Content-Type",
                                safeMime
                            )
                            .header(
                                "Content-Range",
                                "bytes " + offset
                                    + "-" + (end - 1)
                                    + "/" + size
                            )
                            .PUT(
                                HttpRequest.BodyPublishers
                                    .ofByteArray(chunk)
                            )
                            .build();

                    UploadProgress progress;

                    try {

                        HttpResponse<String> response =
                            send(upload);

                        int status =
                            response.statusCode();

                        // 업로드 전체 완료
                        if (status == 200
                            || status == 201) {

                            return completedFile(
                                accessToken,
                                response.body()
                            );
                        }

                        // 청크 수신 완료, 다음 위치 확인
                        if (status == 308) {

                            progress = new UploadProgress(
                                nextOffset(
                                    response.headers()
                                        .firstValue("Range")
                                        .orElse(null),
                                    size
                                ),
                                null
                            );

                            // 일시적 HTTP 오류
                        } else if (
                            status == 429
                                || status >= 500
                        ) {

                            progress = checkUploadStatus(
                                session,
                                accessToken,
                                size
                            );

                        } else {

                            log.warn(
                                "Drive upload failed: HTTP {}",
                                status
                            );

                            throw new BusinessException(
                                ErrorCode.EXTERNAL_API_ERROR
                            );
                        }

                    } catch (IOException e) {

                        // 네트워크 오류 발생 시
                        // Google Drive가 받은 위치를 확인
                        progress = checkUploadStatus(
                            session,
                            accessToken,
                            size
                        );
                    }

                    // 상태 조회 과정에서 완료가 확인된 경우
                    if (progress.completedId() != null) {

                        return getFileMetadata(
                            accessToken,
                            progress.completedId()
                        );
                    }

                    long next =
                        progress.nextOffset();

                    if (next < offset
                        || next > size) {

                        throw new BusinessException(
                            ErrorCode.EXTERNAL_API_ERROR
                        );
                    }

                    // 전송 위치가 계속 그대로라면 중단
                    stalled = next == offset
                        ? stalled + 1
                        : 0;

                    if (stalled >= MAX_STALLED_ATTEMPTS) {

                        throw new BusinessException(
                            ErrorCode.EXTERNAL_API_ERROR
                        );
                    }

                    offset = next;
                }
            }

            // 마지막 완료 응답을 받지 못한 경우
            UploadProgress finalStatus =
                checkUploadStatus(
                    session,
                    accessToken,
                    size
                );

            if (finalStatus.completedId() != null) {

                return getFileMetadata(
                    accessToken,
                    finalStatus.completedId()
                );
            }

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );

        } catch (IOException e) {

            log.warn(
                "Drive file read failed: {}",
                e.getClass().getSimpleName()
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    // =====================================================
    // 7. Resumable Upload 세션 생성
    // =====================================================

    private URI startUpload(
        String token,
        String folderId,
        String fileName,
        String mimeType,
        long fileSize
    ) {

        String metadata =
            "{\"name\":" + jsonQuote(fileName)
                + ",\"parents\":["
                + jsonQuote(folderId)
                + "],\"mimeType\":"
                + jsonQuote(mimeType)
                + "}";

        URI uri = UriComponentsBuilder
            .fromUriString(UPLOAD_URL)
            .queryParam(
                "uploadType",
                "resumable"
            )
            .queryParam(
                "supportsAllDrives",
                true
            )
            .queryParam(
                "fields",
                FILE_FIELDS
            )
            .build()
            .encode()
            .toUri();

        try {

            ResponseEntity<Void> response =
                externalRestClient.post()
                    .uri(uri)
                    .headers(
                        h -> h.setBearerAuth(token)
                    )
                    .contentType(
                        MediaType.APPLICATION_JSON
                    )
                    .header(
                        "X-Upload-Content-Type",
                        mimeType
                    )
                    .header(
                        "X-Upload-Content-Length",
                        Long.toString(fileSize)
                    )
                    .body(metadata)
                    .retrieve()
                    .toBodilessEntity();

            URI session =
                response.getHeaders().getLocation();

            if (!response.getStatusCode()
                .is2xxSuccessful()
                || !validSessionUri(session)) {

                throw new BusinessException(
                    ErrorCode.EXTERNAL_API_ERROR
                );
            }

            return session;

        } catch (RestClientException e) {

            logRestError(
                "Drive upload session",
                e
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    // =====================================================
    // 8. 중단된 업로드 상태 확인
    // =====================================================

    private UploadProgress checkUploadStatus(
        URI session,
        String token,
        long size
    ) {

        HttpRequest probe =
            HttpRequest.newBuilder(session)
                .timeout(
                    Duration.ofSeconds(30)
                )
                .header(
                    "Authorization",
                    "Bearer " + token
                )
                .header(
                    "Content-Range",
                    "bytes */" + size
                )
                .PUT(
                    HttpRequest.BodyPublishers.noBody()
                )
                .build();

        try {

            HttpResponse<String> response =
                send(probe);

            if (response.statusCode() == 308) {

                return new UploadProgress(
                    nextOffset(
                        response.headers()
                            .firstValue("Range")
                            .orElse(null),
                        size
                    ),
                    null
                );
            }

            if (response.statusCode() == 200
                || response.statusCode() == 201) {

                return new UploadProgress(
                    size,
                    responseFileId(
                        response.body()
                    )
                );
            }

            log.warn(
                "Drive upload status probe failed: HTTP {}",
                response.statusCode()
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );

        } catch (IOException e) {

            log.warn(
                "Drive upload status probe network error"
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    /**
     * 업로드 전용 HTTP 요청 처리.
     */
    private static HttpResponse<String> send(
        HttpRequest request
    ) throws IOException {

        try {

            return UPLOAD_HTTP.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    private DriveFile completedFile(
        String token,
        String responseJson
    ) {
        return getFileMetadata(
            token,
            responseFileId(responseJson)
        );
    }

    /**
     * 업로드 완료 JSON에서 파일 ID 확인.
     */
    private static String responseFileId(
        String json
    ) {

        Matcher matcher =
            FILE_ID_IN_JSON.matcher(
                json == null ? "" : json
            );

        if (!matcher.find()) {

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }

        return matcher.group(1);
    }

    /**
     * Range: bytes=0-4194303
     * 다음 전송 시작: 4194304
     */
    private static long nextOffset(
        String range,
        long size
    ) {

        if (range == null || range.isBlank()) {
            return 0;
        }

        Matcher matcher =
            RECEIVED_RANGE.matcher(range);

        if (!matcher.matches()) {

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }

        try {

            long next = Math.addExact(
                Long.parseLong(
                    matcher.group(1)
                ),
                1
            );

            if (next > size) {
                throw new NumberFormatException(
                    "Invalid range"
                );
            }

            return next;

        } catch (
            NumberFormatException
            | ArithmeticException e
        ) {

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    /**
     * Google이 반환한 업로드 세션 URL 검증.
     */
    private static boolean validSessionUri(
        URI uri
    ) {

        if (uri == null
            || !"https".equalsIgnoreCase(
            uri.getScheme()
        )
            || uri.getHost() == null
            || uri.getRawUserInfo() != null
            || (
            uri.getPort() != -1
                && uri.getPort() != 443
        )) {

            return false;
        }

        String host =
            uri.getHost()
                .toLowerCase(Locale.ROOT);

        return host.equals("googleapis.com")
            || host.endsWith(".googleapis.com");
    }

    // =====================================================
    // 9. 파일 메타데이터 조회
    // =====================================================

    public DriveFile getFileMetadata(
        String accessToken,
        String fileId
    ) {

        validateId(fileId);

        UriComponentsBuilder uri =
            UriComponentsBuilder.fromUriString(
                    BASE + "/files/" + fileId
                )
                .queryParam(
                    "fields",
                    FILE_FIELDS
                )
                .queryParam(
                    "supportsAllDrives",
                    true
                );

        DriveFile file = toFile(
            request(accessToken, uri, true)
        );

        if (file == null) {

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }

        return file;
    }

    // =====================================================
    // 10. 파일 휴지통 이동
    // =====================================================

    /**
     * Google Drive에서 영구 삭제하지 않는다.
     * trashed = true
     */
    public DriveFile trashFile(
        String accessToken,
        String fileId
    ) {

        return updateTrashed(
            accessToken,
            fileId,
            true
        );
    }

    // =====================================================
    // 11. 휴지통 파일 복원
    // =====================================================

    /**
     * trashed = false
     */
    public DriveFile restoreFile(
        String accessToken,
        String fileId
    ) {

        return updateTrashed(
            accessToken,
            fileId,
            false
        );
    }

    private DriveFile updateTrashed(
        String accessToken,
        String fileId,
        boolean trashed
    ) {

        validateId(fileId);

        URI uri = UriComponentsBuilder
            .fromUriString(
                BASE + "/files/" + fileId
            )
            .queryParam(
                "supportsAllDrives",
                true
            )
            .queryParam(
                "fields",
                FILE_FIELDS
            )
            .build()
            .encode()
            .toUri();

        try {

            Map<String, Object> body =
                externalRestClient.patch()
                    .uri(uri)
                    .headers(
                        h -> h.setBearerAuth(
                            accessToken
                        )
                    )
                    .contentType(
                        MediaType.APPLICATION_JSON
                    )
                    .body(
                        Map.of(
                            "trashed",
                            trashed
                        )
                    )
                    .retrieve()
                    .body(JSON);

            DriveFile file = toFile(body);

            if (file == null
                || file.trashed() != trashed) {

                throw new BusinessException(
                    ErrorCode.EXTERNAL_API_ERROR
                );
            }

            return file;

        } catch (RestClientException e) {

            logRestError(
                "Drive trash/restore",
                e
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    // =====================================================
    // 12. 파일 다운로드 / PDF 내보내기
    // =====================================================

    /**
     * 일반 파일:
     *   files.get?alt=media
     *
     * Google Docs / Sheets / Slides / Drawings:
     *   files.export?mimeType=application/pdf
     *
     * 호출 전에 Service에서 다음을 검증해야 한다.
     *
     * - 워크스페이스 멤버인지
     * - 선택된 Drive 폴더의 파일인지
     * - 휴지통에 있는 파일이 아닌지
     * - 다운로드 권한이 있는지
     */
    public void streamFile(
        String accessToken,
        String fileId,
        String mimeType,
        OutputStream destination
    ) {

        validateId(fileId);

        if (destination == null) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        URI uri;

        if (isPdfExportable(mimeType)) {

            uri = UriComponentsBuilder
                .fromUriString(
                    BASE + "/files/" + fileId
                        + "/export"
                )
                .queryParam(
                    "mimeType",
                    "application/pdf"
                )
                .build()
                .encode()
                .toUri();

        } else if (
            mimeType != null
                && mimeType.startsWith(
                "application/vnd.google-apps."
            )
        ) {

            // PDF 내보내기를 지원하지 않는
            // Google 고유 형식
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );

        } else {

            uri = UriComponentsBuilder
                .fromUriString(
                    BASE + "/files/" + fileId
                )
                .queryParam(
                    "alt",
                    "media"
                )
                .queryParam(
                    "supportsAllDrives",
                    true
                )
                .build()
                .encode()
                .toUri();
        }

        try {

            transferRestClient.get()
                .uri(uri)
                .headers(
                    h -> h.setBearerAuth(
                        accessToken
                    )
                )
                .exchange((request, response) -> {

                    if (!response.getStatusCode()
                        .is2xxSuccessful()) {

                        log.warn(
                            "Drive download/export failed: HTTP {}",
                            response.getStatusCode().value()
                        );

                        throw new BusinessException(
                            ErrorCode.EXTERNAL_API_ERROR
                        );
                    }

                    try {

                        response.getBody()
                            .transferTo(destination);

                        return null;

                    } catch (IOException e) {
                        log.warn("Drive download/export stream interrupted: {}", e.getClass().getSimpleName());
                        throw new BusinessException(
                            ErrorCode.EXTERNAL_API_ERROR
                        );
                    }
                });

        } catch (RestClientException e) {

            logRestError(
                "Drive download/export",
                e
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    /**
     * PDF 내보내기 지원 Google 문서 형식.
     */
    public static boolean isPdfExportable(
        String mimeType
    ) {

        return "application/vnd.google-apps.document"
            .equals(mimeType)
            || "application/vnd.google-apps.spreadsheet"
            .equals(mimeType)
            || "application/vnd.google-apps.presentation"
            .equals(mimeType)
            || "application/vnd.google-apps.drawing"
            .equals(mimeType);
    }

    // =====================================================
    // 13. 공통 Google API GET 요청
    // =====================================================

    private Map<String, Object> request(
        String token,
        UriComponentsBuilder builder
    ) {
        return request(token, builder, false);
    }

    private Map<String, Object> request(
        String token,
        UriComponentsBuilder builder,
        boolean fileLookup
    ) {

        try {

            Map<String, Object> body =
                externalRestClient.get()
                    .uri(
                        builder.build()
                            .encode()
                            .toUri()
                    )
                    .headers(
                        h -> h.setBearerAuth(token)
                    )
                    .retrieve()
                    .body(JSON);

            if (body == null) {

                throw new BusinessException(
                    ErrorCode.EXTERNAL_API_ERROR
                );
            }

            return body;

        } catch (HttpClientErrorException e) {

            if (fileLookup && e.getStatusCode().value() == 404) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }

            if (e.getStatusCode().value() == 410) {
                throw new CursorExpiredException();
            }

            logRestError(
                "Drive GET",
                e
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );

        } catch (RestClientException e) {

            logRestError(
                "Drive GET",
                e
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    // =====================================================
    // 14. Google 응답을 DriveFile로 변환
    // =====================================================

    private static DriveFile toFile(
        Map<String, Object> json
    ) {

        if (json == null) {
            return null;
        }

        String id = str(json, "id");

        if (id == null) {
            return null;
        }

        List<String> parents =
            new ArrayList<>();

        Object rawParents =
            json.get("parents");

        if (rawParents instanceof List<?> values) {

            for (Object value : values) {

                if (value instanceof String parent) {
                    parents.add(parent);
                }
            }
        }

        return new DriveFile(
            id,
            str(json, "name"),
            str(json, "mimeType"),
            str(json, "description"),
            str(json, "webViewLink"),
            parseDate(
                str(json, "createdTime")
            ),
            parseDate(
                str(json, "modifiedTime")
            ),
            List.copyOf(parents),
            Boolean.TRUE.equals(
                json.get("trashed")
            )
        );
    }

    // =====================================================
    // 15. 날짜 및 JSON 변환
    // =====================================================

    private static LocalDateTime parseDate(
        String raw
    ) {

        return raw == null
            ? null
            : LocalDateTime.ofInstant(
            Instant.parse(raw),
            ZoneOffset.UTC
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(
        Object value
    ) {

        return value instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : null;
    }

    // =====================================================
    // 16. 입력값 검증
    // =====================================================

    private static void validateId(
        String id
    ) {

        if (id == null
            || !id.matches(
            "[A-Za-z0-9_-]+"
        )) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }
    }

    private static String safeFileName(
        String name
    ) {

        if (name == null) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        String result =
            name.replace('\\', '/');

        result = result.substring(
            result.lastIndexOf('/') + 1
        );

        if (result.isBlank()
            || result.length() > 255
            || result.indexOf('\0') >= 0) {

            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        return result;
    }

    private static String safeMimeType(
        String mime
    ) {

        return mime != null
            && mime.matches(
            "[A-Za-z0-9!#$&^_.+-]+/"
                + "[A-Za-z0-9!#$&^_.+-]+"
        )
            ? mime
            : "application/octet-stream";
    }

    /**
     * Google Drive 업로드 메타데이터에 사용할
     * JSON 문자열 이스케이프.
     */
    private static String jsonQuote(
        String value
    ) {

        StringBuilder result =
            new StringBuilder("\"");

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            switch (c) {

                case '"' ->
                    result.append("\\\"");

                case '\\' ->
                    result.append("\\\\");

                case '\n' ->
                    result.append("\\n");

                case '\r' ->
                    result.append("\\r");

                case '\t' ->
                    result.append("\\t");

                case '\b' ->
                    result.append("\\b");

                case '\f' ->
                    result.append("\\f");

                default -> {

                    if (c < 0x20) {

                        result.append(
                            String.format(
                                "\\u%04x",
                                (int) c
                            )
                        );

                    } else {
                        result.append(c);
                    }
                }
            }
        }

        return result.append('"').toString();
    }

    // =====================================================
    // 17. 오류 로깅
    // =====================================================

    private static void logRestError(
        String operation,
        RestClientException e
    ) {

        if (e instanceof RestClientResponseException response) {

            log.warn(
                "{} failed: HTTP {}",
                operation,
                response.getStatusCode().value()
            );

        } else {

            log.warn(
                "{} failed: {}",
                operation,
                e.getClass().getSimpleName()
            );
        }
    }

    // =====================================================
    // 18. 증분 동기화 커서 만료 예외
    // =====================================================

    public static final class CursorExpiredException
        extends RuntimeException {

        public CursorExpiredException() {
            super(
                "Google Drive change cursor expired"
            );
        }
    }
}
