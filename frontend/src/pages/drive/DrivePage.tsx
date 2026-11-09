import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
} from 'react';
import { Link, useParams } from 'react-router';

import { http, ws } from '../../api/client';
import PageHeader from '../../components/PageHeader';

import {
  BTN_PRIMARY,
  BTN_SUB,
  CARD,
  INPUT,
  MUTED,
  SUB_CARD,
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
    const file =
      event.target.files?.[0] ?? null;

    setError(null);
    setNotice(null);

    if (!file) {
      setUploadFile(null);
      return;
    }

    if (file.size === 0) {
      setUploadFile(null);
      setError('빈 파일은 업로드할 수 없습니다.');
      event.target.value = '';
      return;
    }

    if (file.size > MAX_DRIVE_UPLOAD_SIZE) {
      setUploadFile(null);
      setError(
        '100MB 이하 파일만 업로드할 수 있습니다.',
      );
      event.target.value = '';
      return;
    }

    if (
      !file.name.trim() ||
      file.name.length > 255 ||
      file.name.includes('\0')
    ) {
      setUploadFile(null);
      setError('파일 이름이 올바르지 않습니다.');
      event.target.value = '';
      return;
    }

    setUploadFile(file);
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
  // 화면
  // ===================================================

  return (
    <section className="space-y-5">

      {/* ============================================= */}
      {/* 페이지 상단 */}
      {/* ============================================= */}

      <PageHeader
        eyebrow="GOOGLE DRIVE"
        title="자료 (Drive)"
        description="연동한 Google Drive 파일과 Task 산출물을 관리합니다."
        actions={
          <>
            <button
              type="button"
              className={BTN_SUB}
              disabled={loading || isBusy}
              onClick={() => void refresh(false)}
            >
              {working === 'refresh'
                ? '새로고침 중...'
                : '새로고침'}
            </button>

            <button
              type="button"
              className={BTN_PRIMARY}
              disabled={loading || isBusy}
              onClick={() => void refresh(true)}
            >
              {working === 'sync'
                ? '동기화 중...'
                : '↻ Drive 동기화'}
            </button>
          </>
        }
      />

      {/* ============================================= */}
      {/* 오류 메시지 */}
      {/* ============================================= */}

      {error && (
        <div
          role="alert"
          className="rounded-xl border border-rose-400/30 bg-rose-500/10 p-3 text-xs text-rose-600 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      {/* ============================================= */}
      {/* 성공 메시지 */}
      {/* ============================================= */}

      {notice && (
        <div
          role="status"
          className="rounded-xl border border-indigo-400/30 bg-indigo-500/10 p-3 text-xs text-indigo-600 dark:text-indigo-300"
        >
          {notice}
        </div>
      )}

      {/* ============================================= */}
      {/* 메인 콘텐츠 */}
      {/* ============================================= */}

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-12">

        {/* =========================================== */}
        {/* 왼쪽: 업로드 + 파일 목록 */}
        {/* =========================================== */}

        <div className="space-y-4 lg:col-span-8">

          {/* ----------------------------------------- */}
          {/* Google Drive 파일 업로드 */}
          {/* ----------------------------------------- */}

          <div
            className={`${CARD} rounded-[26px] border-2 border-dashed border-indigo-300 p-6 text-center dark:border-white/15`}
          >
            <div className="mb-3 text-3xl">
              ☁️
            </div>

            <h2 className="m-0 text-sm font-bold">
              Google Drive 파일 볼트
            </h2>

            <p className={`mt-2 text-xs leading-relaxed ${MUTED}`}>
              연동된 Google Drive 폴더에 파일을
              직접 업로드할 수 있습니다.
              {' '}
              (최대 100MB)
            </p>

            {folderIds.length > 0 ? (
              <div className="mx-auto mt-4 max-w-lg space-y-3 text-left">

                <label
                  htmlFor="drive-upload-folder"
                  className="block text-xs font-bold"
                >
                  업로드할 폴더
                </label>

                <select
                  id="drive-upload-folder"
                  value={chosenFolderId}
                  onChange={event =>
                    setUploadFolderId(
                      event.target.value,
                    )
                  }
                  disabled={isBusy}
                  className={`${INPUT} w-full text-xs`}
                >
                  {folderIds.map((id, index) => (
                    <option
                      key={id}
                      value={id}
                    >
                      {folderIds.length === 1
                        ? '연동된 Drive 폴더'
                        : `연동 폴더 ${index + 1}`}
                      {' ('}{id}{')'}
                    </option>
                  ))}
                </select>

                <input
                  ref={fileInputRef}
                  type="file"
                  className="hidden"
                  aria-label="업로드할 파일"
                  disabled={isBusy}
                  onChange={handleFileSelect}
                />

                {uploadFile && (
                  <div
                    className={`${SUB_CARD} break-all p-3 text-xs`}
                  >
                    <p className="m-0 font-bold">
                      📄 {uploadFile.name}
                    </p>

                    <p className={`mt-1 ${MUTED}`}>
                      파일 크기:{' '}
                      {formatDriveFileSize(
                        uploadFile.size,
                      )}
                    </p>
                  </div>
                )}

                <div className="flex flex-wrap justify-center gap-2">

                  <button
                    type="button"
                    className={BTN_SUB}
                    disabled={loading || isBusy}
                    onClick={() =>
                      fileInputRef.current?.click()
                    }
                  >
                    파일 선택
                  </button>

                  <button
                    type="button"
                    className={BTN_PRIMARY}
                    disabled={
                      loading ||
                      isBusy ||
                      !uploadFile ||
                      !chosenFolderId
                    }
                    onClick={() =>
                      void handleUpload()
                    }
                  >
                    {working === 'upload'
                      ? '업로드 중...'
                      : '☁️ ToGather에서 업로드'}
                  </button>

                </div>

                {working === 'upload' && (
                  <div
                    role="status"
                    className="rounded-xl bg-indigo-500/10 p-3 text-center text-xs text-indigo-500"
                  >
                    Google Drive에 파일을 업로드하고
                    있습니다.
                    {' '}
                    완료 후 목록이 자동 갱신됩니다.
                  </div>
                )}

              </div>
            ) : (
              <div className="mt-4 space-y-2">
                <p className={`text-xs ${MUTED}`}>
                  {loading
                    ? '연동된 폴더를 확인하고 있습니다...'
                    : '업로드할 폴더가 없습니다. 연동 관리에서 Google Drive 폴더를 선택해 주세요.'}
                </p>

                {!loading && (
                  <Link
                    to={`/workspaces/${workspaceId}/integrations`}
                    className="inline-block text-xs font-bold text-indigo-500 hover:underline"
                  >
                    연동 관리로 이동 →
                  </Link>
                )}
              </div>
            )}

            <a
              href="https://drive.google.com/drive/my-drive"
              target="_blank"
              rel="noopener noreferrer"
              className="mt-4 inline-block text-xs font-bold text-indigo-500 hover:underline"
            >
              Google Drive에서 직접 관리하기 ↗
            </a>
          </div>

          {/* ----------------------------------------- */}
          {/* 파일 목록 및 휴지통 */}
          {/* ----------------------------------------- */}

          <div
            className={`${CARD} space-y-4 rounded-[26px] p-5`}
          >

            <div className="flex flex-wrap items-center justify-between gap-3">

              <div>
                <h2 className="m-0 text-sm font-bold">
                  {showTrash
                    ? 'Drive 휴지통'
                    : '파일 목록'}
                </h2>

                <p className={`mt-1 text-xs ${MUTED}`}>
                  {showTrash
                    ? `복원 가능한 항목 ${trashFiles.length}개`
                    : `총 ${files.length}개 파일`}
                </p>
              </div>

              <div className="flex flex-wrap items-center gap-2">

                <button
                  type="button"
                  disabled={isBusy}
                  onClick={() => {
                    setShowTrash(false);
                    setSelectedId(null);
                    setQuery('');
                  }}
                  className={
                    !showTrash
                      ? BTN_PRIMARY
                      : BTN_SUB
                  }
                >
                  📁 파일
                </button>

                <button
                  type="button"
                  disabled={isBusy}
                  onClick={() => {
                    setShowTrash(true);
                    setSelectedId(null);
                    setQuery('');
                  }}
                  className={
                    showTrash
                      ? BTN_PRIMARY
                      : BTN_SUB
                  }
                >
                  🗑 휴지통
                </button>

              </div>
            </div>

            {/* 파일명 검색 */}

            <input
              type="search"
              value={query}
              onChange={event =>
                setQuery(event.target.value)
              }
              placeholder={
                showTrash
                  ? '휴지통 파일명 검색'
                  : '파일명 검색'
              }
              aria-label="파일명 검색"
              className={`${INPUT} w-full !py-2 text-xs`}
            />

            {/* 파일 목록 본문 */}

            {loading ? (
              <div className="py-12 text-center">
                <p className={`text-xs ${MUTED}`}>
                  Google Drive 파일을 불러오는 중입니다...
                </p>
              </div>
            ) : visibleFiles.length === 0 ? (
              <div className="py-12 text-center">

                <div className="mb-3 text-3xl">
                  {showTrash ? '🗑️' : '📂'}
                </div>

                <p className={`text-xs ${MUTED}`}>
                  {query
                    ? '검색 결과가 없습니다.'
                    : showTrash
                      ? '휴지통에 표시할 파일이 없습니다.'
                      : '동기화된 파일이 없습니다.'}
                </p>

                {!query && !showTrash && (
                  <Link
                    to={`/workspaces/${workspaceId}/integrations`}
                    className="mt-3 inline-block text-xs font-bold text-indigo-500 hover:underline"
                  >
                    연동 관리에서 Drive 폴더 확인하기 →
                  </Link>
                )}

              </div>
            ) : (

              <div className="space-y-2.5">

                {visibleFiles.map(file => {

                  const selected =
                    selectedFile?.contentRefId ===
                    file.contentRefId;

                  const format =
                    fileFormat(file);

                  const isWorkingFile =
                    workingFileId ===
                    file.contentRefId;

                  return (
                    <button
                      key={file.contentRefId}
                      type="button"
                      onClick={() => {
                        if (showTrash) {
                          void handleRestore(file);
                        } else {
                          setSelectedId(file.contentRefId);
                        }
                      }}
                      disabled={isBusy}
                      aria-pressed={selected}
                      className={`flex w-full items-center justify-between gap-3 rounded-2xl p-4 text-left transition ${
                        selected
                          ? 'border border-indigo-400/40 bg-gradient-to-br from-indigo-500/10 to-purple-500/10 shadow-sm'
                          : SUB_CARD
                      }`}
                    >

                      <span className="flex min-w-0 items-center gap-3">

                        <span
                          className={`shrink-0 rounded-xl px-2.5 py-1 text-[10px] font-bold ${
                            format === 'ZIP'
                              ? 'bg-amber-500/20 text-amber-500'
                              : 'bg-indigo-500/15 text-indigo-500'
                          }`}
                        >
                          {format}
                        </span>

                        <span className="min-w-0">

                          <span className="block truncate text-xs font-bold">
                            {file.title || '(제목 없음)'}
                          </span>

                          <span
                            className={`mt-1 block text-[11px] ${MUTED}`}
                          >
                            최종 수정{' '}
                            {formatDate(
                              file.lastModifiedAt,
                            )}
                          </span>

                        </span>

                      </span>

                      <span className="shrink-0 text-[10px] font-bold text-indigo-500">

                        {isWorkingFile
                          ? working === 'delete'
                            ? '삭제 중...'
                            : working === 'restore'
                              ? '복원 중...'
                              : '처리 중...'
                          : showTrash
                            ? '복원하기 →'
                            : selected
                              ? taskLoading
                                ? 'Task 조회 중'
                                : `🔗 관련 Task ${tasks.length}개`
                              : '상세 보기 →'}

                      </span>

                    </button>
                  );
                })}

              </div>
            )}
          </div>
        </div>

        {/* =========================================== */}
        {/* 오른쪽: 파일 상세 패널 */}
        {/* =========================================== */}

        <aside
          className={`${CARD} rounded-[26px] p-6 lg:col-span-4`}
        >

          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 pb-3 dark:border-white/10">

            <h2 className="m-0 text-sm font-bold">
              파일 프리뷰 인스펙터
            </h2>

            {selectedFile?.deepLink?.startsWith(
              'https://',
            ) && (
              <a
                href={selectedFile.deepLink}
                target="_blank"
                rel="noopener noreferrer"
                className="text-xs font-bold text-indigo-500 hover:underline"
              >
                Drive 원본 ↗
              </a>
            )}

          </div>

          {selectedFile ? (
            <>

              {/* 파일 제목 */}

              <h3 className="mt-4 break-all text-base font-bold">
                {selectedFile.title || '(제목 없음)'}
              </h3>

              {/* 파일 형식 및 수정일 */}

              <p className={`mt-2 text-xs ${MUTED}`}>
                {fileFormat(selectedFile)}
                {' · '}
                {formatDate(
                  selectedFile.lastModifiedAt,
                )}
              </p>

              {/* 동기화 오류 */}

              {selectedFile.syncFailed && (
                <div className="mt-3 rounded-xl bg-amber-500/10 p-3 text-xs text-amber-500">
                  마지막 콘텐츠 갱신에 실패했습니다.
                </div>
              )}

              {/* 파일 메타데이터 */}

              <div className="mt-5 rounded-2xl border border-indigo-400/30 bg-indigo-500/10 p-4">

                <span className="text-[10px] font-bold uppercase text-indigo-500">
                  파일 메타데이터
                </span>

                <p className="mt-3 whitespace-pre-wrap break-words text-xs leading-relaxed">
                  {fileDescription(selectedFile) ||
                    '파일 본문 메타데이터가 없습니다.'}
                </p>

              </div>

              {/* ------------------------------------- */}
              {/* 미리보기 및 다운로드 */}
              {/* ------------------------------------- */}

              {!showTrash && (
                <div className="mt-5 grid grid-cols-2 gap-2">

                  <button
                    type="button"
                    disabled={
                      isBusy ||
                      !canPreviewDriveFile(selectedFile)
                    }
                    onClick={() =>
                      openPreview(selectedFile)
                    }
                    className={`${BTN_SUB} text-center`}
                    title={
                      canPreviewDriveFile(selectedFile)
                        ? 'ToGather에서 파일 미리보기'
                        : '이 파일 형식은 미리보기를 지원하지 않습니다.'
                    }
                  >
                    👁 미리보기
                  </button>

                  <a
                    href={
                      getDriveDownloadUrl(
                        workspaceId,
                        selectedFile.contentRefId,
                      )
                    }
                    className={`${BTN_PRIMARY} text-center`}
                    aria-label="파일 다운로드"
                  >
                    ⬇ 다운로드
                  </a>

                </div>
              )}

              {/* ------------------------------------- */}
              {/* 관련 Task */}
              {/* ------------------------------------- */}

              {!showTrash && (
                <div className="mt-5 border-t border-slate-200 pt-4 dark:border-white/10">

                  <h4 className="m-0 text-xs font-bold">
                    🔗 연결된 Task
                  </h4>

                  {taskLoading ? (
                    <p className={`mt-3 text-xs ${MUTED}`}>
                      Task를 불러오는 중입니다...
                    </p>
                  ) : taskError ? (
                    <p className="mt-3 text-xs text-rose-500">
                      {taskError}
                    </p>
                  ) : tasks.length === 0 ? (
                    <p className={`mt-3 text-xs ${MUTED}`}>
                      연결된 Task가 없습니다.
                    </p>
                  ) : (
                    <div className="mt-3 space-y-2">

                      {tasks.map(task => (
                        <div
                          key={task.taskId}
                          className={`${SUB_CARD} p-3 text-xs`}
                        >

                          <p className="m-0 font-bold">
                            {task.title}
                          </p>

                          <p className={`mt-2 ${MUTED}`}>
                            {task.status === 'DONE'
                              ? '완료'
                              : '진행 중'}
                            {' · '}
                            진행률 {task.progress}%
                          </p>

                        </div>
                      ))}

                    </div>
                  )}

                  <Link
                    to={`/workspaces/${workspaceId}/tasks`}
                    className="mt-4 inline-block text-xs font-bold text-indigo-500 hover:underline"
                  >
                    Task 목록으로 이동 →
                  </Link>

                </div>
              )}

              {/* ------------------------------------- */}
              {/* 파일 삭제 / 복원 */}
              {/* ------------------------------------- */}

              <div className="mt-5 border-t border-slate-200 pt-4 dark:border-white/10">

                {showTrash ? (
                  <>

                    <p className={`mb-3 text-xs ${MUTED}`}>
                      휴지통에 있는 파일입니다.
                      복원하면 일반 파일 목록에 다시 표시됩니다.
                    </p>

                    <button
                      type="button"
                      disabled={isBusy}
                      className={BTN_PRIMARY}
                      onClick={() =>
                        void handleRestore(
                          selectedFile,
                        )
                      }
                    >
                      {working === 'restore'
                        ? '복원 중...'
                        : '↩ Google Drive에서 복원'}
                    </button>

                  </>
                ) : (
                  <>

                    <p className={`mb-3 text-xs ${MUTED}`}>
                      파일 삭제 시 Google Drive 휴지통으로
                      이동하며, 영구 삭제되지 않습니다.
                    </p>

                    <button
                      type="button"
                      disabled={isBusy}
                      onClick={() =>
                        void handleDelete(
                          selectedFile,
                        )
                      }
                      className="text-xs font-bold text-rose-500 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {working === 'delete'
                        ? '삭제 중...'
                        : '🗑 휴지통으로 이동'}
                    </button>

                  </>
                )}

              </div>

            </>
          ) : (

            <div className="py-12 text-center">

              <div className="mb-3 text-3xl">
                📄
              </div>

              <p className={`text-xs ${MUTED}`}>
                왼쪽에서 파일을 선택하면
                상세 정보가 표시됩니다.
              </p>

            </div>
          )}

        </aside>
      </div>

      {/* ============================================= */}
      {/* Drive 동기화 상태 */}
      {/* ============================================= */}

      {statuses.length > 0 && (
        <div className={`${CARD} space-y-3 p-5`}>

          <div className="flex flex-wrap items-center justify-between gap-2">

            <h3 className="m-0 text-xs font-bold">
              Drive 동기화 상태
            </h3>

            <span className={`text-[11px] ${MUTED}`}>
              화면 자동 조회: 60초 간격
            </span>

          </div>

          <div className="space-y-2">

            {statuses.map(status => (

              <div
                key={status.targetId}
                className={`flex flex-wrap items-center justify-between gap-2 rounded-xl p-3 text-xs ${MUTED} bg-slate-50 dark:bg-white/5`}
              >

                <span
                  className="max-w-[65%] truncate"
                  title={status.targetId}
                >
                  폴더 ID: {status.targetId}
                </span>

                <span
                  className={
                    status.failed
                      ? 'font-bold text-rose-500'
                      : 'font-bold text-emerald-500'
                  }
                >
                  {status.failed
                    ? '동기화 실패'
                    : '정상'}
                </span>

                <span>
                  {formatDate(
                    status.lastSyncedAt,
                  )}
                </span>

                {status.failed && status.lastError && (
                  <p className="w-full text-rose-500">
                    {status.lastError}
                  </p>
                )}

              </div>

            ))}

          </div>
        </div>
      )}

      {/* ============================================= */}
      {/* 파일 미리보기 모달 */}
      {/* ============================================= */}

      {previewFile && (
        <div
          role="presentation"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
          onClick={event => {
            if (
              event.target === event.currentTarget
            ) {
              setPreviewFile(null);
            }
          }}
        >

          <div
            role="dialog"
            aria-modal="true"
            aria-label="Google Drive 파일 미리보기"
            className="flex h-[85vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl dark:bg-slate-900"
          >

            {/* 모달 헤더 */}

            <div className="flex items-center justify-between gap-3 border-b border-slate-200 p-4 dark:border-white/10">

              <div className="min-w-0">

                <h3 className="truncate text-sm font-bold">
                  {previewFile.title || '(제목 없음)'}
                </h3>

                <p className={`mt-1 text-xs ${MUTED}`}>
                  Google Drive 파일 미리보기
                </p>

              </div>

              <div className="flex shrink-0 items-center gap-2">

                <a
                  href={
                    getDrivePreviewUrl(
                      workspaceId,
                      previewFile.contentRefId,
                    )
                  }
                  target="_blank"
                  rel="noopener noreferrer"
                  className={BTN_SUB}
                >
                  새 탭
                </a>

                <button
                  type="button"
                  onClick={() =>
                    setPreviewFile(null)
                  }
                  className={BTN_PRIMARY}
                  aria-label="미리보기 닫기"
                >
                  닫기 ✕
                </button>

              </div>

            </div>

            {/* 파일 본문 */}

            <div className="min-h-0 flex-1 bg-slate-100 dark:bg-slate-950">

              <iframe
                key={previewFile.contentRefId}
                src={
                  getDrivePreviewUrl(
                    workspaceId,
                    previewFile.contentRefId,
                  )
                }
                title={
                  `${previewFile.title || '파일'} 미리보기`
                }
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
