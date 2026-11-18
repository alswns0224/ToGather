import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
} from 'react';
import { Link, useParams } from 'react-router';

import { http, ws } from '../../api/client';
import PageHeader from '../../components/PageHeader';
import NavIcon from '../../layouts/NavIcon';

import {
  MUTED,
} from '../../components/ui';

import {
  canPreviewDriveFile,
  deleteDriveFile,
  formatDriveFileSize,
  getDriveDownloadUrl,
  getDriveFiles,
  getDriveFolders,
  getDrivePreviewUrl,
  getDriveSyncStatuses,
  getDriveTrashFiles,
  MAX_DRIVE_UPLOAD_SIZE,
  restoreDriveFile,
  syncDrive,
  uploadDriveFile,
  type DriveFile,
  type DriveSyncStatus,
} from './api';

// =====================================================
// 타입
// =====================================================

interface LinkedTask {
  taskId: number;
  title: string;
  status: string;
  progress: number;
}

type WorkingAction =
  | 'refresh'
  | 'sync'
  | 'upload'
  | 'delete'
  | 'restore'
  | null;

// 화면 데이터 자동 조회 간격: 60초
const AUTO_REFRESH_INTERVAL = 60_000;

// =====================================================
// 유틸리티
// =====================================================

function formatDate(value: string | null): string {
  return value
    ? value.replace('T', ' ').slice(0, 16)
    : '정보 없음';
}

function fileFormat(file: DriveFile): string {
  const ext =
    file.title?.match(/\.([a-z0-9]+)$/i)?.[1];

  if (ext) return ext.toUpperCase();

  const mime =
    file.content
      ?.match(/^파일 유형:\s*(.+)$/m)?.[1]
      ?.trim() || '';

  if (mime.includes('spreadsheet')) return 'SHEET';
  if (mime.includes('presentation')) return 'SLIDES';
  if (mime.includes('document')) return 'DOC';
  if (mime.includes('pdf')) return 'PDF';
  if (mime.startsWith('image/')) return 'IMAGE';

  return 'FILE';
}

function fileDescription(file: DriveFile): string {
  return (
    file.content
      ?.split('\n')
      .filter(line => !line.startsWith('파일 유형:'))
      .join('\n')
      .trim() || ''
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : '요청 중 오류가 발생했습니다.';
}

// =====================================================
// Google Drive 페이지
// =====================================================

export default function DrivePage() {
  const { workspaceId = '' } = useParams();

  // ---------------------------------------------------
  // 1. 파일 및 연동 상태
  // ---------------------------------------------------

  const [files, setFiles] = useState<DriveFile[]>([]);
  const [trashFiles, setTrashFiles] =
    useState<DriveFile[]>([]);

  const [statuses, setStatuses] =
    useState<DriveSyncStatus[]>([]);

  const [folders, setFolders] = useState<string[]>([]);
  const [foldersLoaded, setFoldersLoaded] = useState(false);

  const [selectedId, setSelectedId] =
    useState<number | null>(null);

  const [showTrash, setShowTrash] = useState(false);

  // ---------------------------------------------------
  // 2. Task 상태
  // ---------------------------------------------------

  const [tasks, setTasks] = useState<LinkedTask[]>([]);
  const [taskLoading, setTaskLoading] = useState(false);
  const [taskError, setTaskError] =
    useState<string | null>(null);

  // ---------------------------------------------------
  // 3. 화면 상태
  // ---------------------------------------------------

  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);

  const [working, setWorking] =
    useState<WorkingAction>(null);

  const [workingFileId, setWorkingFileId] =
    useState<number | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] =
    useState<string | null>(null);

  const workingRef = useRef(false);
  const dataVersionRef = useRef(0);

  const isBusy = working !== null;

  // ---------------------------------------------------
  // 4. 업로드 상태
  // ---------------------------------------------------

  const fileInputRef = useRef<HTMLInputElement>(null);
  const dragDepthRef = useRef(0);
  const [isDraggingFile, setIsDraggingFile] = useState(false);

  const [uploadFile, setUploadFile] =
    useState<File | null>(null);

  const [uploadFolderId, setUploadFolderId] =
    useState('');

  // ---------------------------------------------------
  // 5. 미리보기 상태
  // ---------------------------------------------------

  const [previewFile, setPreviewFile] =
    useState<DriveFile | null>(null);

  // ===================================================
  // 6. 최초 데이터 조회 + 60초 자동 갱신
  // ===================================================

  useEffect(() => {
    if (!workspaceId) {
      setLoading(false);
      return;
    }

    let active = true;
    let polling = false;

    setLoading(true);
    setError(null);
    setNotice(null);

    setFiles([]);
    setTrashFiles([]);
    setStatuses([]);
    setFolders([]);
    setFoldersLoaded(false);

    setSelectedId(null);
    setShowTrash(false);
    setQuery('');
    setUploadFile(null);
    dragDepthRef.current = 0;
    setIsDraggingFile(false);
    setUploadFolderId('');
    setPreviewFile(null);

    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }

    async function initialLoad() {
      const [
        fileResult,
        statusResult,
        folderResult,
        trashResult,
      ] = await Promise.allSettled([
        getDriveFiles(workspaceId),
        getDriveSyncStatuses(workspaceId),
        getDriveFolders(workspaceId),
        getDriveTrashFiles(workspaceId),
      ]);

      if (!active) return;

      if (fileResult.status === 'fulfilled') {
        setFiles(fileResult.value);
      } else {
        setError(errorMessage(fileResult.reason));
      }

      if (statusResult.status === 'fulfilled') {
        setStatuses(statusResult.value);
      }

      if (folderResult.status === 'fulfilled') {
        setFolders(folderResult.value);
        setFoldersLoaded(true);
      }

      if (trashResult.status === 'fulfilled') {
        setTrashFiles(trashResult.value);
      }

      setLoading(false);
    }

    void initialLoad();

    // 서버에 저장된 최신 파일 정보를 60초마다 조회.
    // Google Drive API 동기화 자체는 실행하지 않는다.
    const intervalId = window.setInterval(() => {
      if (!active || polling || workingRef.current) {
        return;
      }

      polling = true;

      const version = dataVersionRef.current;

      void Promise.allSettled([
        getDriveFiles(workspaceId),
        getDriveSyncStatuses(workspaceId),
        getDriveFolders(workspaceId),
        getDriveTrashFiles(workspaceId),
      ])
        .then(([
                 fileResult,
                 statusResult,
                 folderResult,
                 trashResult,
               ]) => {
          if (
            !active ||
            workingRef.current ||
            version !== dataVersionRef.current
          ) {
            return;
          }

          if (fileResult.status === 'fulfilled') {
            setFiles(fileResult.value);
          }

          if (statusResult.status === 'fulfilled') {
            setStatuses(statusResult.value);
          }

          if (folderResult.status === 'fulfilled') {
            setFolders(folderResult.value);
            setFoldersLoaded(true);
          }

          if (trashResult.status === 'fulfilled') {
            setTrashFiles(trashResult.value);
          }
        })
        .finally(() => {
          polling = false;
        });
    }, AUTO_REFRESH_INTERVAL);

    return () => {
      active = false;
      window.clearInterval(intervalId);
    };
  }, [workspaceId]);

  // ===================================================
  // 7. 최신 파일·폴더·동기화 상태 재조회
  // ===================================================

  async function reloadAll(): Promise<void> {
    if (!workspaceId) return;

    const [
      fileResult,
      statusResult,
      folderResult,
      trashResult,
    ] = await Promise.allSettled([
      getDriveFiles(workspaceId),
      getDriveSyncStatuses(workspaceId),
      getDriveFolders(workspaceId),
      getDriveTrashFiles(workspaceId),
    ]);

    if (statusResult.status === 'fulfilled') {
      setStatuses(statusResult.value);
    }

    if (folderResult.status === 'fulfilled') {
      setFolders(folderResult.value);
      setFoldersLoaded(true);
    }

    if (trashResult.status === 'fulfilled') {
      setTrashFiles(trashResult.value);
    }

    if (fileResult.status === 'rejected') {
      throw fileResult.reason;
    }

    const nextFiles = fileResult.value;

    setFiles(nextFiles);

    // 일반 파일 목록과 휴지통 목록 모두에서
    // 찾을 수 없는 파일은 선택 해제
    const nextTrash =
      trashResult.status === 'fulfilled'
        ? trashResult.value
        : trashFiles;

    setSelectedId(current => {
      if (current == null) return null;

      const exists =
        nextFiles.some(
          file => file.contentRefId === current,
        ) ||
        nextTrash.some(
          file => file.contentRefId === current,
        );

      return exists ? current : null;
    });
  }

  // ===================================================
  // 8. 업로드 가능한 폴더
  // ===================================================

  const folderIds = useMemo(() => {
    // 선택된 연동 폴더 API가 정상 응답하면
    // 동기화 상태가 아닌 실제 연동 폴더 목록을 사용
    if (foldersLoaded) {
      return [...new Set(folders)];
    }

    // 구형 서버 또는 일시적인 폴더 API 오류 시
    // 기존 동기화 상태의 targetId를 보조로 활용
    return [
      ...new Set(
        statuses
          .map(status => status.targetId)
          .filter(Boolean),
      ),
    ];
  }, [folders, foldersLoaded, statuses]);

  const chosenFolderId =
    folderIds.includes(uploadFolderId)
      ? uploadFolderId
      : (folderIds[0] ?? '');

  const canSelectUpload = !loading && !isBusy && Boolean(chosenFolderId);

  // ===================================================
  // 9. 파일 검색 및 선택
  // ===================================================

  const currentFiles = showTrash
    ? trashFiles
    : files;

  const visibleFiles = useMemo(() => {
    const keyword = query.trim().toLowerCase();

    if (!keyword) return currentFiles;

    return currentFiles.filter(file =>
      (file.title || '')
        .toLowerCase()
        .includes(keyword),
    );
  }, [currentFiles, query]);

  const selectedFile =
    visibleFiles.find(
      file => file.contentRefId === selectedId,
    ) ??
    visibleFiles[0] ??
    null;

  const selectedContentId =
    selectedFile?.contentRefId;

  // ===================================================
  // 10. 선택한 파일의 관련 Task 조회
  // ===================================================

  useEffect(() => {
    if (
      !workspaceId ||
      showTrash ||
      selectedContentId == null
    ) {
      setTasks([]);
      setTaskError(null);
      setTaskLoading(false);
      return;
    }

    let active = true;

    setTasks([]);
    setTaskLoading(true);
    setTaskError(null);

    void http
      .get<LinkedTask[]>(
        ws(
          workspaceId,
          `/contents/${selectedContentId}/tasks`,
        ),
      )
      .then(data => {
        if (active) {
          setTasks(data);
        }
      })
      .catch(err => {
        if (active) {
          setTaskError(errorMessage(err));
        }
      })
      .finally(() => {
        if (active) {
          setTaskLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [
    workspaceId,
    selectedContentId,
    showTrash,
  ]);

  // ===================================================
  // 11. 수동 새로고침 / Google Drive 동기화
  // ===================================================

  async function refresh(runSync: boolean) {
    if (
      !workspaceId ||
      loading ||
      workingRef.current
    ) {
      return;
    }

    workingRef.current = true;
    dataVersionRef.current += 1;

    setWorking(runSync ? 'sync' : 'refresh');
    setError(null);
    setNotice(null);

    try {
      let syncResult: DriveSyncStatus[] | null = null;

      if (runSync) {
        syncResult = await syncDrive(workspaceId);
        setStatuses(syncResult);
      }

      await reloadAll();

      if (!runSync) {
        setNotice('파일 목록을 새로고침했습니다.');
      } else if (syncResult?.length === 0) {
        setNotice(
          '선택된 동기화 폴더가 없습니다. 연동 관리를 확인해 주세요.',
        );
      } else if (
        syncResult?.some(status => status.failed)
      ) {
        setNotice(
          '일부 폴더의 동기화에 실패했습니다. 상태를 확인해 주세요.',
        );
      } else {
        setNotice('Google Drive 동기화가 완료됐습니다.');
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      workingRef.current = false;
      setWorking(null);
    }
  }

  // ===================================================
  // 12. 업로드할 파일 선택
  // ===================================================

  function handleFileSelect(
    event: ChangeEvent<HTMLInputElement>,
  ) {
    selectUploadFile(event.target.files?.[0] ?? null);
    event.target.value = '';
  }

  function selectUploadFile(file: File | null) {
    if (!canSelectUpload || workingRef.current) return;

    setError(null);
    setNotice(null);

    if (!file) {
      setUploadFile(null);
      return;
    }

    if (file.size === 0) {
      setUploadFile(null);
      setError('빈 파일은 업로드할 수 없습니다.');
      return;
    }

    if (file.size > MAX_DRIVE_UPLOAD_SIZE) {
      setUploadFile(null);
      setError(
        '100MB 이하 파일만 업로드할 수 있습니다.',
      );
      return;
    }

    if (
      !file.name.trim() ||
      file.name.length > 255 ||
      file.name.includes('\0')
    ) {
      setUploadFile(null);
      setError('파일 이름이 올바르지 않습니다.');
      return;
    }

    setUploadFile(file);
  }

  function handleDragEnter(event: DragEvent<HTMLDivElement>) {
    if (!event.dataTransfer.types.includes('Files')) return;
    event.preventDefault();
    dragDepthRef.current += 1;
    if (canSelectUpload) setIsDraggingFile(true);
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    if (!event.dataTransfer.types.includes('Files')) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = canSelectUpload ? 'copy' : 'none';
  }

  function handleDragLeave() {
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) setIsDraggingFile(false);
  }

  function handleFileDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    dragDepthRef.current = 0;
    setIsDraggingFile(false);
    if (!canSelectUpload || workingRef.current || !event.dataTransfer.types.includes('Files')) return;

    const files = event.dataTransfer.files;
    const hasDirectory = Array.from(event.dataTransfer.items)
      .some(item => item.webkitGetAsEntry?.()?.isDirectory);
    if (hasDirectory || files.length !== 1) {
      setUploadFile(null);
      setNotice(null);
      setError('파일을 한 개씩 업로드해 주세요. 폴더는 업로드할 수 없습니다.');
      return;
    }
    selectUploadFile(files[0]);
  }

  // ===================================================
  // 13. Google Drive 파일 업로드
  // ===================================================

  async function handleUpload() {
    if (
      !workspaceId ||
      !uploadFile ||
      !chosenFolderId ||
      loading ||
      workingRef.current
    ) {
      return;
    }

    workingRef.current = true;
    dataVersionRef.current += 1;

    setWorking('upload');
    setError(null);
    setNotice(null);

    const fileName = uploadFile.name;

    try {
      const uploaded = await uploadDriveFile(
        workspaceId,
        chosenFolderId,
        uploadFile,
      );

      // 업로드 성공 즉시 목록에 표시
      setFiles(current => [
        uploaded,
        ...current.filter(
          file =>
            file.contentRefId !==
            uploaded.contentRefId,
        ),
      ]);

      setShowTrash(false);
      setQuery('');
      setSelectedId(uploaded.contentRefId);

      setUploadFile(null);

      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }

      setNotice(
        `'${fileName}' 파일 업로드가 완료됐습니다.`,
      );

      // 실제 DB 목록 재조회
      try {
        await reloadAll();
      } catch {
        setNotice(
          '업로드는 완료됐지만 목록 갱신에 실패했습니다. 새로고침해 주세요.',
        );
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      workingRef.current = false;
      setWorking(null);
    }
  }

  // ===================================================
  // 14. Google Drive 파일 삭제
  // ===================================================

  async function handleDelete(file: DriveFile) {
    if (
      !workspaceId ||
      workingRef.current
    ) {
      return;
    }

    const confirmed = window.confirm(
      `'${file.title || '(제목 없음)'}' 파일을 Google Drive 휴지통으로 이동할까요?\n\n영구 삭제가 아니며 휴지통에서 복원할 수 있습니다.`,
    );

    if (!confirmed) return;

    workingRef.current = true;
    dataVersionRef.current += 1;

    setWorking('delete');
    setWorkingFileId(file.contentRefId);
    setError(null);
    setNotice(null);

    try {
      await deleteDriveFile(
        workspaceId,
        file.contentRefId,
      );

      // 목록에서 즉시 제거
      setFiles(current =>
        current.filter(
          item =>
            item.contentRefId !== file.contentRefId,
        ),
      );

      setSelectedId(null);
      setPreviewFile(null);

      setNotice(
        `'${file.title || '파일'}'을 휴지통으로 이동했습니다.`,
      );

      try {
        await reloadAll();
      } catch {
        setNotice(
          '휴지통 이동은 완료됐지만 목록 갱신에 실패했습니다.',
        );
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      workingRef.current = false;
      setWorking(null);
      setWorkingFileId(null);
    }
  }

  // ===================================================
  // 15. Google Drive 휴지통 복원
  // ===================================================

  async function handleRestore(file: DriveFile) {
    if (
      !workspaceId ||
      workingRef.current
    ) {
      return;
    }

    workingRef.current = true;
    dataVersionRef.current += 1;

    setWorking('restore');
    setWorkingFileId(file.contentRefId);
    setError(null);
    setNotice(null);

    try {
      const restored = await restoreDriveFile(
        workspaceId,
        file.contentRefId,
      );

      // 휴지통 목록에서 즉시 제거
      setTrashFiles(current =>
        current.filter(
          item =>
            item.contentRefId !== file.contentRefId,
        ),
      );

      // 일반 파일 목록에 반영
      setFiles(current => [
        restored,
        ...current.filter(
          item =>
            item.contentRefId !==
            restored.contentRefId,
        ),
      ]);

      setShowTrash(false);
      setQuery('');
      setSelectedId(restored.contentRefId);

      setNotice(
        `'${file.title || '파일'}' 복원이 완료됐습니다.`,
      );

      try {
        await reloadAll();
      } catch {
        setNotice(
          '복원은 완료됐지만 목록 갱신에 실패했습니다.',
        );
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      workingRef.current = false;
      setWorking(null);
      setWorkingFileId(null);
    }
  }

  // ===================================================
  // 16. 미리보기 열기 / 닫기
  // ===================================================

  function openPreview(file: DriveFile) {
    if (!canPreviewDriveFile(file)) {
      setError(
        '이 파일 형식은 ToGather 미리보기를 지원하지 않습니다. 원본을 확인하거나 다운로드해 주세요.',
      );
      return;
    }

    setError(null);
    setPreviewFile(file);
  }

  useEffect(() => {
    if (!previewFile) return;

    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setPreviewFile(null);
      }
    }

    window.addEventListener('keydown', handleEscape);

    return () => {
      window.removeEventListener(
        'keydown',
        handleEscape,
      );
    };
  }, [previewFile]);

  // ===================================================
  // 화면 (팀원 UI 시안: 업로드 줄 + 파일 표 | 파일 상세)
  // ===================================================

  const lastSyncedAt = statuses
    .map((s) => s.lastSyncedAt)
    .filter((v): v is string => !!v)
    .sort()
    .pop();

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="drive" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="Drive"
        description="팀의 파일을 모으고, 작업과 연결하세요."
        actions={
          <>
            {folderIds.length > 0 ? (
              <span className="badge badge-success">● 연결됨</span>
            ) : (
              !loading && <span className="badge">연결 안 됨</span>
            )}
            {folderIds.length > 0 && (
              <span className="text-xs text-[var(--muted)]">
                {folderIds.length}개 폴더 연결{lastSyncedAt && ` · 마지막 동기화 ${formatDate(lastSyncedAt)}`}
              </span>
            )}
            <button
              type="button"
              className="btn !h-[34px]"
              disabled={loading || isBusy}
              onClick={() => void refresh(true)}
            >
              {working === 'sync' ? '동기화 중...' : working === 'refresh' ? '불러오는 중...' : '↻ 동기화'}
            </button>
          </>
        }
      />

      {error && (
        <div role="alert" className="alert alert-error">
          {error}
        </div>
      )}
      {notice && (
        <div role="status" className="alert alert-success">
          {notice}
        </div>
      )}

      {/* 업로드 줄 */}
      <div
        role="region"
        aria-label="Google Drive 파일 업로드 영역"
        aria-busy={working === 'upload'}
        onDragEnter={handleDragEnter}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleFileDrop}
        className={`drive-drop${isDraggingFile && canSelectUpload ? ' dragging' : ''}`}
      >
        <div className="flex min-w-0 items-center gap-3">
          <span className="drive-drop-icon">☁️</span>
          <div className="min-w-0">
            {uploadFile ? (
              <>
                <div className="truncate text-[15px] font-bold">📄 {uploadFile.name}</div>
                <div className="text-[13px] text-[var(--muted)]">{formatDriveFileSize(uploadFile.size)}</div>
              </>
            ) : (
              <>
                <div className="text-[15px] font-bold">
                  {isDraggingFile ? '여기에 놓으면 업로드할 파일로 선택돼요' : '파일을 끌어놓거나 선택하세요'}
                </div>
                <div className="text-[13px] text-[var(--muted)]">
                  {folderIds.length > 0
                    ? '파일당 최대 100MB'
                    : loading
                      ? '연동된 폴더를 확인하고 있습니다...'
                      : '업로드할 폴더가 없어요. 연동 관리에서 Drive 폴더를 선택해 주세요.'}
                </div>
              </>
            )}
          </div>
        </div>

        <input
          ref={fileInputRef}
          type="file"
          className="hidden"
          aria-label="업로드할 파일"
          disabled={!canSelectUpload}
          onChange={handleFileSelect}
        />

        {folderIds.length > 0 ? (
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="drive-upload-folder" className="text-[13px] text-[var(--muted)]">
              업로드할 폴더
            </label>
            <select
              id="drive-upload-folder"
              value={chosenFolderId}
              onChange={(event) => setUploadFolderId(event.target.value)}
              disabled={isBusy}
              className="input !w-auto max-w-[220px]"
            >
              {folderIds.map((id, index) => (
                <option key={id} value={id}>
                  {folderIds.length === 1 ? '연동된 Drive 폴더' : `연동 폴더 ${index + 1}`}
                </option>
              ))}
            </select>
            <button
              type="button"
              className="btn btn-primary"
              disabled={loading || isBusy}
              onClick={() => fileInputRef.current?.click()}
            >
              파일 선택
            </button>
            <button
              type="button"
              className="btn"
              disabled={loading || isBusy || !uploadFile || !chosenFolderId}
              onClick={() => void handleUpload()}
            >
              {working === 'upload' ? '업로드 중...' : '업로드'}
            </button>
          </div>
        ) : (
          !loading && (
            <Link to={`/workspaces/${workspaceId}/integrations`} className="btn">
              연동 관리로 이동 →
            </Link>
          )
        )}
      </div>

      <div className="drive-board">
        {/* 파일 목록 */}
        <div className="card overflow-hidden !p-0">
          <div className="flex flex-wrap items-center justify-between gap-3 px-5 pt-5 pb-3">
            <div className="flex items-center gap-1.5">
              <strong className="text-[17px]">{showTrash ? 'Drive 휴지통' : '파일 목록'}</strong>
              <span className="badge">{showTrash ? trashFiles.length : files.length}</span>
            </div>
            <a
              href="https://drive.google.com/drive/my-drive"
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn-small"
            >
              ↗ Drive에서 열기
            </a>
          </div>

          <div className="flex flex-wrap items-center gap-2 border-b border-[var(--border-soft)] px-5 pb-4">
            <div className="segmented">
              <button
                type="button"
                disabled={isBusy}
                className={!showTrash ? 'active' : ''}
                onClick={() => {
                  setShowTrash(false);
                  setSelectedId(null);
                  setQuery('');
                }}
              >
                파일 ({files.length})
              </button>
              <button
                type="button"
                disabled={isBusy}
                className={showTrash ? 'active' : ''}
                onClick={() => {
                  setShowTrash(true);
                  setSelectedId(null);
                  setQuery('');
                }}
              >
                휴지통 ({trashFiles.length})
              </button>
            </div>
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={showTrash ? '🔍 휴지통 파일명 검색' : '🔍 파일명 검색'}
              aria-label="파일명 검색"
              className="input min-w-[180px] flex-1"
            />
          </div>

          <div className="drive-row drive-head">
            <span>파일 이름</span>
            <span>최근 수정</span>
            <span>{showTrash ? '' : '연결'}</span>
          </div>

          {loading ? (
            <div className="py-12 text-center text-[13px] text-[var(--muted)]">Google Drive 파일을 불러오는 중입니다...</div>
          ) : visibleFiles.length === 0 ? (
            <div className="py-12 text-center text-[13px] text-[var(--muted)]">
              {query ? '검색 결과가 없습니다.' : showTrash ? '휴지통에 표시할 파일이 없습니다.' : '동기화된 파일이 없습니다.'}
              {!query && !showTrash && (
                <div className="mt-2">
                  <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리에서 Drive 폴더 확인하기 →</Link>
                </div>
              )}
            </div>
          ) : (
            visibleFiles.map((file) => {
              const selected = selectedFile?.contentRefId === file.contentRefId;
              const format = fileFormat(file);
              const isWorkingFile = workingFileId === file.contentRefId;
              return (
                <button
                  key={file.contentRefId}
                  type="button"
                  onClick={() => setSelectedId(file.contentRefId)}
                  disabled={isBusy}
                  aria-pressed={selected}
                  className={`drive-row drive-item${selected ? ' selected' : ''}`}
                >
                  <span className="flex min-w-0 items-center gap-3">
                    <span className={`drive-ext ext-${format.toLowerCase()}`}>{format.slice(0, 4)}</span>
                    <span className="min-w-0">
                      <span className="block truncate text-[15px] font-bold">{file.title || '(제목 없음)'}</span>
                      {file.syncFailed && <span className="text-xs text-[var(--warning)]">동기화 실패</span>}
                    </span>
                  </span>
                  <span className="text-sm">{formatDate(file.lastModifiedAt)}</span>
                  <span className="text-xs font-bold text-[var(--primary)]">
                    {isWorkingFile
                      ? working === 'delete'
                        ? '삭제 중...'
                        : working === 'restore'
                          ? '복원 중...'
                          : '처리 중...'
                      : showTrash
                        ? '복원 가능'
                        : selected && !taskLoading
                          ? `팀 작업 ${tasks.length}개`
                          : ''}
                  </span>
                </button>
              );
            })
          )}
        </div>

        {/* 파일 상세 */}
        <aside className="card drive-side">
          <strong className="text-[17px]">파일 상세</strong>

          {selectedFile ? (
            <>
              <div className="flex items-start gap-3">
                <span className={`drive-ext ext-${fileFormat(selectedFile).toLowerCase()}`}>
                  {fileFormat(selectedFile).slice(0, 4)}
                </span>
                <div className="min-w-0">
                  <div className="break-all text-base font-bold">{selectedFile.title || '(제목 없음)'}</div>
                  <div className="text-[13px] text-[var(--muted)]">
                    {fileFormat(selectedFile)} · 수정 {formatDate(selectedFile.lastModifiedAt)}
                  </div>
                </div>
              </div>

              {selectedFile.syncFailed && (
                <div className="alert alert-error small">마지막 콘텐츠 갱신에 실패했습니다.</div>
              )}

              <div className="drive-meta">
                <div className="mb-1 text-xs font-bold text-[var(--primary)]">파일 정보</div>
                <p className="m-0 whitespace-pre-wrap break-words text-[13px] leading-relaxed">
                  {fileDescription(selectedFile) || '파일 본문 메타데이터가 없습니다.'}
                </p>
              </div>

              {!showTrash && (
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    disabled={isBusy || !canPreviewDriveFile(selectedFile)}
                    onClick={() => openPreview(selectedFile)}
                    className="btn !h-11"
                    title={
                      canPreviewDriveFile(selectedFile)
                        ? 'ToGather에서 파일 미리보기'
                        : '이 파일 형식은 미리보기를 지원하지 않습니다.'
                    }
                  >
                    ↗ 크게 보기
                  </button>
                  <a
                    href={getDriveDownloadUrl(workspaceId, selectedFile.contentRefId)}
                    className="btn btn-primary !h-11"
                    aria-label="파일 다운로드"
                  >
                    ⬇ 다운로드
                  </a>
                </div>
              )}

              {!showTrash && (
                <div className="flex flex-col gap-2 border-t border-[var(--border-soft)] pt-4">
                  <div className="flex items-center gap-1.5">
                    <strong className="text-[15px]">연결된 Task</strong>
                    <span className="badge">{tasks.length}</span>
                  </div>
                  {taskLoading ? (
                    <p className="m-0 text-[13px] text-[var(--muted)]">팀 작업을 불러오는 중입니다...</p>
                  ) : taskError ? (
                    <p className="m-0 text-[13px] text-[var(--danger)]">{taskError}</p>
                  ) : tasks.length === 0 ? (
                    <p className="m-0 text-[13px] text-[var(--muted)]">연결된 팀 작업이 없습니다.</p>
                  ) : (
                    tasks.map((task) => (
                      <Link
                        key={task.taskId}
                        to={`/workspaces/${workspaceId}/tasks/${task.taskId}`}
                        className="notion-task !p-3"
                      >
                        <strong className="text-sm text-[var(--text)]">☑ {task.title}</strong>
                        <div className="mt-1 flex items-center gap-2 text-xs">
                          <span className={`badge ${task.status === 'DONE' ? 'badge-success' : 'badge-primary'}`}>
                            {task.status === 'DONE' ? '완료' : '진행 중'}
                          </span>
                          <span className="text-[var(--muted)]">진행률 {task.progress}%</span>
                        </div>
                      </Link>
                    ))
                  )}
                </div>
              )}

              <div className="border-t border-[var(--border-soft)] pt-4">
                {showTrash ? (
                  <>
                    <p className="mb-3 text-[13px] text-[var(--muted)]">
                      휴지통에 있는 파일입니다. 복원하면 일반 파일 목록에 다시 표시됩니다.
                    </p>
                    <button
                      type="button"
                      disabled={isBusy}
                      className="btn btn-primary w-full"
                      onClick={() => void handleRestore(selectedFile)}
                    >
                      {working === 'restore' ? '복원 중...' : '↩ Google Drive에서 복원'}
                    </button>
                  </>
                ) : (
                  <button
                    type="button"
                    disabled={isBusy}
                    onClick={() => void handleDelete(selectedFile)}
                    className="btn btn-danger !h-11 w-full"
                    title="Google Drive 휴지통으로 이동하며, 영구 삭제되지 않습니다."
                  >
                    {working === 'delete' ? '삭제 중...' : '🗑 휴지통으로 이동'}
                  </button>
                )}
              </div>
            </>
          ) : (
            <div className="py-12 text-center text-[13px] text-[var(--muted)]">
              왼쪽에서 파일을 선택하면 상세 정보가 표시됩니다.
            </div>
          )}
        </aside>
      </div>

      {/* Drive 동기화 상태 */}
      {statuses.some((s) => s.failed) && (
        <div className="card flex flex-col gap-2">
          <strong className="text-sm">동기화에 실패한 폴더</strong>
          {statuses
            .filter((s) => s.failed)
            .map((status) => (
              <div key={status.targetId} className="text-[13px] text-[var(--danger)]">
                폴더 {status.targetId} · {formatDate(status.lastSyncedAt)}
                {status.lastError && ` · ${status.lastError}`}
              </div>
            ))}
        </div>
      )}

      {/* 파일 미리보기 모달 */}
      {previewFile && (
        <div
          role="presentation"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
          onClick={(event) => {
            if (event.target === event.currentTarget) {
              setPreviewFile(null);
            }
          }}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Google Drive 파일 미리보기"
            className="flex h-[85vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl bg-[var(--surface)] shadow-2xl"
          >
            <div className="flex items-center justify-between gap-3 border-b border-[var(--border)] p-4">
              <div className="min-w-0">
                <h3 className="m-0 truncate text-sm font-bold">{previewFile.title || '(제목 없음)'}</h3>
                <p className={`m-0 mt-1 text-xs ${MUTED}`}>Google Drive 파일 미리보기</p>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <a
                  href={getDrivePreviewUrl(workspaceId, previewFile.contentRefId)}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="btn"
                >
                  새 탭
                </a>
                <button
                  type="button"
                  onClick={() => setPreviewFile(null)}
                  className="btn btn-primary"
                  aria-label="미리보기 닫기"
                >
                  닫기 ✕
                </button>
              </div>
            </div>
            <div className="min-h-0 flex-1 bg-[var(--surface-2)]">
              <iframe
                key={previewFile.contentRefId}
                src={getDrivePreviewUrl(workspaceId, previewFile.contentRefId)}
                title={`${previewFile.title || '파일'} 미리보기`}
                className="h-full w-full border-0"
                referrerPolicy="no-referrer"
              />
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
