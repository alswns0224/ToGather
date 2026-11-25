import { api, http, ws } from '../../api/client';

/**
 * Google Drive 콘텐츠 응답
 * 백엔드 ContentRefDTO와 동일한 구조
 */
export interface DriveFile {
  contentRefId: number;
  provider: 'GOOGLE_DRIVE';
  contentType: string;
  externalId: string;
  title: string | null;
  content: string | null;
  deepLink: string | null;
  fileFormat: string | null;
  lastModifiedAt: string | null;
  messageCount: number;
  syncFailed: boolean;
  fileSize: number | null;
}

/**
 * Google Drive 동기화 상태
 */
export interface DriveSyncStatus {
  provider: 'GOOGLE_DRIVE';
  targetId: string;
  lastSyncedAt: string | null;
  lastAttemptAt: string | null;
  failed: boolean;
  lastError: string | null;
}

/**
 * Google Drive 업로드 최대 크기: 100MB
 */
export const MAX_DRIVE_UPLOAD_SIZE =
  100 * 1024 * 1024;

/**
 * 파일 크기를 읽기 쉬운 문자열로 변환
 */
export function formatDriveFileSize(
  bytes: number | null | undefined,
): string {
  if (bytes == null || !Number.isSafeInteger(bytes) || bytes < 0) return '크기 정보 없음';
  if (bytes < 1024) {
    return `${bytes} B`;
  }

  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }

  if (bytes >= 1024 ** 4) return `${(bytes / 1024 ** 4).toFixed(2)} TB`;
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  return `${(
    bytes /
    (1024 * 1024)
  ).toFixed(2)} MB`;
}

/**
 * ContentRef ID 검증
 */
function validateContentRefId(
  contentRefId: number,
): void {
  if (
    !Number.isSafeInteger(contentRefId) ||
    contentRefId <= 0
  ) {
    throw new Error(
      '올바르지 않은 파일 ID입니다.',
    );
  }
}

/**
 * 공통 API 호출용 경로
 *
 * 반환 예시:
 * /workspaces/1/drive/files/123
 *
 * api(), http는 /api를 자동으로 추가한다.
 */
function driveFilePath(
  workspaceId: string,
  contentRefId: number,
): string {
  validateContentRefId(contentRefId);

  return ws(
    workspaceId,
    `/drive/files/${contentRefId}`,
  );
}

/**
 * 브라우저 직접 접근용 경로
 *
 * 반환 예시:
 * /api/workspaces/1/drive/files/123
 *
 * a href, iframe src에는 /api를 직접 붙여야 한다.
 */
function driveBrowserPath(
  workspaceId: string,
  contentRefId: number,
): string {
  return `/api${driveFilePath(
    workspaceId,
    contentRefId,
  )}`;
}

// =====================================================
// 1. Google Drive 연동 폴더 조회
// =====================================================

/**
 * GET /api/workspaces/{workspaceId}/drive/folders
 *
 * 연동 관리에서 선택한 Google Drive 폴더 조회
 */
export const getDriveFolders = (
  workspaceId: string,
): Promise<string[]> =>
  http.get<string[]>(
    ws(workspaceId, '/drive/folders'),
  );

/**
 * GET /api/workspaces/{workspaceId}/drive/folders/names
 *
 * 연동한 폴더 ID와 이름 (업로드할 폴더 고르기에 이름으로 보여준다)
 */
export const getDriveFolderNames = (workspaceId: string) =>
  http.get<{ folderId: string; name: string | null }[]>(ws(workspaceId, '/drive/folders/names'));

export interface DriveFolderHierarchy {
  folderId: string;
  name: string | null;
  ancestors: { folderId: string; name: string | null }[];
}

export const getDriveFolderHierarchy = (workspaceId: string) =>
  http.get<DriveFolderHierarchy[]>(ws(workspaceId, '/drive/folders/hierarchy'));

// =====================================================
// 1-b. Google Drive 하위 폴더 조회
// =====================================================

export interface DriveSubfolder {
  id: string;
  name: string | null;
}

/**
 * GET /api/workspaces/{workspaceId}/drive/folders/{folderId}/subfolders
 */
export const getDriveSubfolders = (
  workspaceId: string,
  folderId: string,
): Promise<DriveSubfolder[]> =>
  http.get<DriveSubfolder[]>(
    ws(workspaceId, `/drive/folders/${encodeURIComponent(folderId)}/subfolders`),
  );

export interface DriveDirectFile {
  id: string;
  name: string | null;
  mimeType: string | null;
  webViewLink: string | null;
  modifiedAt: string | null;
  fileSize: number | null;
}

/**
 * GET /api/workspaces/{workspaceId}/drive/folders/{folderId}/files
 *
 * Google Drive API에서 직접 조회 (하위 폴더 탐색용)
 */
export const getDriveDirectFiles = (
  workspaceId: string,
  folderId: string,
): Promise<DriveDirectFile[]> =>
  http.get<DriveDirectFile[]>(
    ws(workspaceId, `/drive/folders/${encodeURIComponent(folderId)}/files`),
  );

// =====================================================
// 2. Google Drive 파일 목록 조회
// =====================================================

/**
 * GET /api/workspaces/{workspaceId}/drive/files
 *
 * folderId 생략: 선택한 모든 폴더
 * folderId 지정: 해당 폴더
 */
export const getDriveFiles = (
  workspaceId: string,
  folderId?: string,
): Promise<DriveFile[]> => {
  const query = folderId
    ? `?folderId=${encodeURIComponent(folderId)}`
    : '';

  return http.get<DriveFile[]>(
    ws(
      workspaceId,
      `/drive/files${query}`,
    ),
  );
};

// =====================================================
// 3. Google Drive 파일 상세 조회
// =====================================================

/**
 * GET /api/workspaces/{workspaceId}/drive/files/{id}
 */
export const getDriveFile = (
  workspaceId: string,
  contentRefId: number,
): Promise<DriveFile> =>
  http.get<DriveFile>(
    driveFilePath(
      workspaceId,
      contentRefId,
    ),
  );

// =====================================================
// 4. Google Drive 즉시 동기화
// =====================================================

/**
 * POST /api/workspaces/{workspaceId}/sync/GOOGLE_DRIVE
 *
 * 기존 SyncController 재사용
 */
export const syncDrive = (
  workspaceId: string,
): Promise<DriveSyncStatus[]> =>
  http.post<DriveSyncStatus[]>(
    ws(
      workspaceId,
      '/sync/GOOGLE_DRIVE',
    ),
  );

// =====================================================
// 5. Google Drive 동기화 상태 조회
// =====================================================

/**
 * GET /api/workspaces/{workspaceId}/sync/GOOGLE_DRIVE
 */
export const getDriveSyncStatuses = (
  workspaceId: string,
): Promise<DriveSyncStatus[]> =>
  http.get<DriveSyncStatus[]>(
    ws(
      workspaceId,
      '/sync/GOOGLE_DRIVE',
    ),
  );

// =====================================================
// 6. Google Drive 파일 업로드
// =====================================================

/**
 * POST /api/workspaces/{workspaceId}/drive/files
 *
 * Content-Type: multipart/form-data
 *
 * 요청:
 * - folderId
 * - file
 *
 * 최대 100MB
 */
export interface DriveUploadCandidate {
  fileId: string;
  name: string;
  mimeType: string | null;
  fileSize: number | null;
  lastModifiedAt: string | null;
  version: string | null;
  deepLink: string | null;
  overwriteAllowed: boolean;
}

export interface DriveUploadOptions {
  mode: 'ASK' | 'CREATE_NEW' | 'OVERWRITE';
  targetFileId?: string;
  expectedVersion?: string;
}

export const getDriveUploadCandidates = (workspaceId: string, folderId: string, name: string) => {
  const params = new URLSearchParams({ folderId, name });
  return http.get<DriveUploadCandidate[]>(ws(workspaceId, `/drive/upload-candidates?${params}`));
};

export const uploadDriveFile = async (
  workspaceId: string,
  folderId: string,
  file: File,
  options?: DriveUploadOptions,
): Promise<DriveFile> => {

  // 빈 파일 검사
  if (file.size === 0) {
    throw new Error(
      '빈 파일은 업로드할 수 없습니다.',
    );
  }

  // 100MB 제한
  if (file.size > MAX_DRIVE_UPLOAD_SIZE) {
    throw new Error(
      '100MB 이하 파일만 업로드할 수 있습니다.',
    );
  }

  // 파일 이름 검증
  if (
    !file.name.trim() ||
    file.name.length > 255 ||
    file.name.includes('\0')
  ) {
    throw new Error(
      '파일 이름이 올바르지 않습니다.',
    );
  }

  // 업로드 폴더 검증
  const selectedFolderId = folderId.trim();

  if (!selectedFolderId) {
    throw new Error(
      '업로드할 Google Drive 폴더를 선택해 주세요.',
    );
  }

  // multipart/form-data 생성
  const formData = new FormData();

  formData.append(
    'folderId',
    selectedFolderId,
  );

  formData.append(
    'file',
    file,
    file.name,
  );
  if (options) {
    formData.append('mode', options.mode);
    if (options.targetFileId) formData.append('targetFileId', options.targetFileId);
    if (options.expectedVersion) formData.append('expectedVersion', options.expectedVersion);
  }

  // FormData 사용 시 Content-Type을 직접 지정하지 않음
  // client.ts의 api()가 boundary를 자동 처리
  return api<DriveFile>(
    ws(
      workspaceId,
      '/drive/files',
    ),
    {
      method: 'POST',
      body: formData,
    },
  );
};

// =====================================================
// 7. Google Drive 휴지통 (팀장 Drive의 ToGather 휴지통 폴더)
// =====================================================

/**
 * 휴지통 항목
 * - TOGATHER: ToGather 휴지통 폴더에 있는 파일·폴더 (itemId로 복원·영구 삭제)
 * - DRIVE: Drive 웹에서 Google Drive 휴지통으로 보낸 수집 파일 (contentRefId로 복원)
 */
export interface DriveTrashItem {
  itemId: string;
  name: string | null;
  folder: boolean;
  mimeType: string | null;
  fileSize: number | null;
  trashedAt: string | null;
  trashedBy: number | null;
  trashedByName: string | null;
  contentRefId: number | null;
  source: 'TOGATHER' | 'DRIVE';
}

/** GET /api/workspaces/{workspaceId}/drive/trash */
export const getDriveTrash = (workspaceId: string): Promise<DriveTrashItem[]> =>
  http.get<DriveTrashItem[]>(ws(workspaceId, '/drive/trash'));

/** POST /api/workspaces/{workspaceId}/drive/items/{itemId}/trash — 하위 폴더·파일을 휴지통으로 */
export const trashDriveItem = (workspaceId: string, itemId: string): Promise<void> =>
  http.post<void>(ws(workspaceId, `/drive/items/${encodeURIComponent(itemId)}/trash`));

/** POST /api/workspaces/{workspaceId}/drive/trash/{itemId}/restore */
export const restoreDriveTrashItem = (workspaceId: string, itemId: string): Promise<void> =>
  http.post<void>(ws(workspaceId, `/drive/trash/${encodeURIComponent(itemId)}/restore`));

/** DELETE /api/workspaces/{workspaceId}/drive/trash/{itemId} — 영구 삭제 (팀장·관리자) */
export const deleteDriveTrashItem = (workspaceId: string, itemId: string): Promise<void> =>
  http.delete<void>(ws(workspaceId, `/drive/trash/${encodeURIComponent(itemId)}`));

/** DELETE /api/workspaces/{workspaceId}/drive/trash — 휴지통 비우기 (팀장·관리자) */
export const emptyDriveTrash = (workspaceId: string) =>
  http.delete<{ deleted: number; failed: number }>(ws(workspaceId, '/drive/trash'));

/** Drive 소유자(팀장)와 소유권 정리·인계 상태 */
export interface DriveOwnershipStatus {
  ownerUserId: number | null;
  ownerName: string | null;
  ownerConnected: boolean;
  trashFolderReady: boolean;
  pendingOwnershipCount: number;
  lastConsolidatedAt: string | null;
  transfer: {
    status: 'RUNNING' | 'DONE';
    fromUserId: number;
    fromName: string | null;
    toUserId: number;
    toName: string | null;
    doneCount: number;
    remainingCount: number;
    failedCount: number;
    lastError: string | null;
  } | null;
}

/** GET /api/workspaces/{workspaceId}/drive/ownership */
export const getDriveOwnership = (workspaceId: string): Promise<DriveOwnershipStatus> =>
  http.get<DriveOwnershipStatus>(ws(workspaceId, '/drive/ownership'));

// =====================================================
// 8. Google Drive 파일 삭제
// =====================================================

/**
 * DELETE /api/workspaces/{workspaceId}/drive/files/{id}
 *
 * Google Drive 휴지통으로 이동
 * 영구 삭제 아님
 *
 * 성공: HTTP 204 No Content
 */
export const deleteDriveFile = async (
  workspaceId: string,
  contentRefId: number,
): Promise<void> => {
  await http.delete<void>(
    driveFilePath(
      workspaceId,
      contentRefId,
    ),
  );
};

// =====================================================
// 9. Google Drive 파일 복원
// =====================================================

/**
 * POST /api/workspaces/{workspaceId}/drive/files/{id}/restore
 *
 * 복원된 ContentRefDTO 반환
 */
export const restoreDriveFile = (
  workspaceId: string,
  contentRefId: number,
): Promise<DriveFile> =>
  http.post<DriveFile>(
    `${driveFilePath(
      workspaceId,
      contentRefId,
    )}/restore`,
  );

// =====================================================
// 10. Google Drive 파일 다운로드 URL
// =====================================================

/**
 * GET /api/workspaces/{workspaceId}/drive/files/{id}/download
 *
 * 일반 파일: 원본 다운로드
 * Google Workspace 문서: PDF 다운로드
 *
 * 브라우저 href 전용 URL
 *
 * 중요:
 * api()를 거치지 않으므로 /api 접두사 필요
 */
export const getDriveDownloadUrl = (
  workspaceId: string,
  contentRefId: number,
): string =>
  `${driveBrowserPath(
    workspaceId,
    contentRefId,
  )}/download`;

// =====================================================
// 11. Google Drive 파일 미리보기 URL
// =====================================================

/**
 * GET /api/workspaces/{workspaceId}/drive/files/{id}/preview
 *
 * 브라우저 iframe 또는 새 탭에서 사용
 *
 * 중요:
 * api()를 거치지 않으므로 /api 접두사 필요
 */
export const getDrivePreviewUrl = (
  workspaceId: string,
  contentRefId: number,
): string =>
  `${driveBrowserPath(
    workspaceId,
    contentRefId,
  )}/preview`;

// =====================================================
// 12. 파일 MIME 타입 추출
// =====================================================

/**
 * ContentRef.content에 저장된 MIME 타입 추출
 *
 * 예시:
 * 파일 유형: application/pdf
 * 설명: 회의 자료
 *
 * 결과:
 * application/pdf
 */
function getDriveMimeType(
  file: DriveFile,
): string {
  const match = file.content?.match(
    /^파일 유형:\s*([^\r\n]+)/m,
  );

  return match?.[1]?.trim().toLowerCase() ?? '';
}

// =====================================================
// 13. 미리보기 지원 여부
// =====================================================

/**
 * Google Drive 파일 미리보기 지원 여부
 *
 * 지원:
 * - PDF
 * - TXT
 * - PNG
 * - JPEG
 * - GIF
 * - WEBP
 * - Google Docs
 * - Google Sheets
 * - Google Slides
 * - Google Drawings
 *
 * 최종 검증은 백엔드에서 수행
 */
export function canPreviewDriveFile(
  file: DriveFile,
): boolean {

  const mimeType = getDriveMimeType(file);

  // Google Workspace 문서
  const googleWorkspaceTypes = new Set([
    'application/vnd.google-apps.document',
    'application/vnd.google-apps.spreadsheet',
    'application/vnd.google-apps.presentation',
    'application/vnd.google-apps.drawing',
  ]);

  if (googleWorkspaceTypes.has(mimeType)) {
    return true;
  }

  // 일반 미리보기 지원 MIME 타입
  const supportedMimeTypes = new Set([
    'application/pdf',
    'text/plain',
    'image/png',
    'image/jpeg',
    'image/gif',
    'image/webp',
  ]);

  if (mimeType) {
    // MIME 타입 정보가 있는 경우
    // 확장자보다 실제 MIME 타입을 우선한다.
    return supportedMimeTypes.has(mimeType);
  }

  // MIME 타입 정보가 없는 경우에만
  // 파일 확장자를 보조적으로 사용한다.
  const extension = file.title
    ?.match(/\.([a-z0-9]+)$/i)?.[1]
    ?.toLowerCase() ?? '';

  const supportedExtensions = new Set([
    'pdf',
    'txt',
    'png',
    'jpg',
    'jpeg',
    'gif',
    'webp',
  ]);

  return supportedExtensions.has(extension);
}
