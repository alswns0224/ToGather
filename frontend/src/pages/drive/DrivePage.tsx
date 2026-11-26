import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
} from 'react';
import { Link, useParams } from 'react-router';
import { useWorkspaceEvents } from '../../hooks/useWorkspaceEvents';

import { ApiError, http, ws } from '../../api/client';
import DriveUploadConflict from './DriveUploadConflict';
import DriveFolderPicker, { type DriveFolderPathEntry } from './DriveFolderPicker';
import DriveFolderBreadcrumbs from './DriveFolderBreadcrumbs';
import PageHeader from '../../components/PageHeader';
import NavIcon from '../../layouts/NavIcon';

import {
  MUTED,
} from '../../components/ui';

import {
  canPreviewDriveFile,
  createDriveFolder,
  deleteDriveFile,
  deleteDriveTrashItem,
  emptyDriveTrash,
  formatDriveFileSize,
  getDriveDirectFiles,
  getDriveDownloadUrl,
  getDriveFiles,
  getDriveOwnership,
  getDriveSubfolders,
  getDriveUploadCandidates,
  getDriveFolderHierarchy,
  getDriveFolders,
  getDrivePreviewUrl,
  getDriveSyncStatuses,
  getDriveTrash,
  MAX_DRIVE_UPLOAD_SIZE,
  restoreDriveFile,
  restoreDriveTrashItem,
  syncDrive,
  trashDriveItem,
  uploadDriveFile,
  type DriveDirectFile,
  type DriveFile,
  type DriveOwnershipStatus,
  type DriveSubfolder,
  type DriveSyncStatus,
  type DriveTrashItem,
  type DriveUploadCandidate,
  type DriveUploadOptions,
} from './api';
import { getWorkspace } from '../workspace/api';

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
  | 'check-upload'
  | 'delete'
  | 'restore'
  | 'empty'
  | 'create-folder'
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

/** 수집하지 않은 항목(하위 폴더 파일·휴지통)의 형식 표시 */
function itemFormat(name: string | null, mimeType: string | null): string {
  const mime = mimeType || '';
  if (mime.includes('spreadsheet')) return 'SHEET';
  if (mime.includes('presentation')) return 'SLIDES';
  if (mime.includes('document')) return 'DOC';
  if (mime.includes('pdf')) return 'PDF';
  if (mime.startsWith('image/')) return 'IMAGE';
  return name?.match(/\.([a-z0-9]+)$/i)?.[1]?.toUpperCase() || 'FILE';
}

function FolderGlyph() {
  return (
    <span className="drive-ext ext-folder">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
      </svg>
    </span>
  );
}

function fileDescription(file: DriveFile): string {
  return (
    file.content
      ?.split('\n\n본문:\n')[0] // 검색용 본문은 설명이 아니다 (서버가 보통 빼고 보낸다)
      .split('\n')
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
  return <DriveContent key={workspaceId} workspaceId={workspaceId} />;
}

function DriveContent({ workspaceId }: { workspaceId: string }) {

  // ---------------------------------------------------
  // 1. 파일 및 연동 상태
  // ---------------------------------------------------

  const [files, setFiles] = useState<DriveFile[]>([]);
  const [trashFiles, setTrashFiles] =
    useState<DriveTrashItem[]>([]);
  // Drive 소유자(팀장) 연동·소유권 인계 상태, 내 역할 (영구 삭제는 팀장·관리자만)
  const [ownership, setOwnership] =
    useState<DriveOwnershipStatus | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);

  const [statuses, setStatuses] =
    useState<DriveSyncStatus[]>([]);

  const [folders, setFolders] = useState<string[]>([]);
  const [foldersLoaded, setFoldersLoaded] = useState(false);

  const [selectedId, setSelectedId] =
    useState<number | null>(null);

  const [showTrash, setShowTrash] = useState(false);

  // ---------------------------------------------------
  // 1-b. 폴더 탐색기 상태
  // ---------------------------------------------------

  const [activeFolderId, setActiveFolderId] =
    useState<string | null>(null);
  const [folderPath, setFolderPath] =
    useState<{ id: string; name: string }[]>([]);
  const [subfolders, setSubfolders] =
    useState<DriveSubfolder[]>([]);
  const [subfoldersLoading, setSubfoldersLoading] =
    useState(false);
  const [directFiles, setDirectFiles] =
    useState<DriveDirectFile[]>([]);

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
  const [workingItemId, setWorkingItemId] =
    useState<string | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] =
    useState<string | null>(null);

  const workingRef = useRef(false);
  const dataVersionRef = useRef(0);
  // 새 폴더 이름 입력칸. null이면 닫혀 있다.
  const [newFolderName, setNewFolderName] = useState<string | null>(null);

  const isBusy = working !== null;

  // ---------------------------------------------------
  // 4. 업로드 상태
  // ---------------------------------------------------

  const fileInputRef = useRef<HTMLInputElement>(null);
  const dragDepthRef = useRef(0);
  const [isDraggingFile, setIsDraggingFile] = useState(false);

  const [uploadFile, setUploadFile] =
    useState<File | null>(null);

  const [uploadRootFolderId, setUploadRootFolderId] =
    useState<string | null>(null);
  const [uploadFolderPath, setUploadFolderPath] =
    useState<DriveFolderPathEntry[]>([]);
  const [uploadCandidates, setUploadCandidates] = useState<DriveUploadCandidate[] | null>(null);

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
    setUploadRootFolderId(null);
    setUploadFolderPath([]);
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
        getDriveTrash(workspaceId),
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
    void getDriveOwnership(workspaceId).then(value => { if (active) setOwnership(value); }).catch(() => undefined);
    void getWorkspace(workspaceId)
      .then(value => { if (active) setIsAdmin(value.myRole === 'OWNER' || value.myRole === 'ADMIN'); })
      .catch(() => undefined);

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
        getDriveTrash(workspaceId),
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

  // 다른 팀원이 올리거나 지우거나 폴더를 만들면(서버 알림) 1분을 기다리지 않고 바로 다시 불러온다.
  // 내가 작업 중이면 그 작업이 끝날 때 다시 불러오므로 건너뛴다.
  useWorkspaceEvents(workspaceId, (event) => {
    if (event.provider !== 'GOOGLE_DRIVE' || workingRef.current) return;
    void reloadAll().catch(() => undefined);
    setFolderHierarchyVersion((value) => value + 1);
  });

  // ===================================================
  // 7. 최신 파일·폴더·동기화 상태 재조회
  // ===================================================

  async function refreshOwnership() {
    try {
      setOwnership(await getDriveOwnership(workspaceId));
    } catch {
      // 소유권 상태는 안내용이라 실패해도 화면을 막지 않는다.
    }
  }

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
      getDriveTrash(workspaceId),
    ]);
    void refreshOwnership();

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

  // 업로드할 폴더 고르기에 보여줄 폴더 이름 (연동 관리에서 고를 때 저장한 이름)
  const [folderNames, setFolderNames] = useState<Record<string, string>>({});
  const [folderHierarchy, setFolderHierarchy] = useState<Record<string, DriveFolderPathEntry[]>>({});
  const [folderHierarchyLoading, setFolderHierarchyLoading] = useState(true);
  const [folderHierarchyError, setFolderHierarchyError] = useState<string | null>(null);
  const [folderHierarchyVersion, setFolderHierarchyVersion] = useState(0);

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

  const folderIdsKey = folderIds.join('|');
  const folderSyncKey = statuses.map(status => status.lastSyncedAt ?? '').join('|');
  useEffect(() => {
    if (!folderIdsKey) { setFolderHierarchyLoading(false); return; }
    let active = true;
    setFolderHierarchyLoading(true);
    setFolderHierarchyError(null);
    getDriveFolderHierarchy(workspaceId).then(list => {
      if (!active) return;
      setFolderNames(Object.fromEntries(list.filter(folder => folder.name).map(folder => [folder.folderId, folder.name as string])));
      setFolderHierarchy(Object.fromEntries(list.map(folder => [folder.folderId,
        folder.ancestors.map(parent => ({ id: parent.folderId, name: parent.name || '(이름 없음)' })),
      ])));
    }).catch(failure => { if (active) setFolderHierarchyError(errorMessage(failure)); })
      .finally(() => { if (active) setFolderHierarchyLoading(false); });
    return () => { active = false; };
  }, [workspaceId, folderIdsKey, folderSyncKey, folderHierarchyVersion]);

  const browseRootFolders = useMemo(() => {
    const linked = new Set(folderIds);
    return folderIds.filter(id => !(folderHierarchy[id] ?? []).some(parent => linked.has(parent.id)))
      .map((id, index) => ({ id, name: folderNames[id] ?? `폴더 ${index + 1}` }));
  }, [folderIds, folderHierarchy, folderNames]);

  // 이미 선택한 하위 폴더도 실제 최상위 연동 폴더와 그 아래 경로로 정규화한다.
  useEffect(() => {
    if (!activeFolderId) return;
    const ancestors = folderHierarchy[activeFolderId] ?? [];
    const rootIndex = ancestors.findIndex(parent => folderIds.includes(parent.id));
    if (rootIndex < 0) return;
    setActiveFolderId(ancestors[rootIndex].id);
    setFolderPath([...ancestors.slice(rootIndex + 1), { id: activeFolderId, name: folderNames[activeFolderId] ?? '(이름 없음)' }, ...folderPath]);
  }, [activeFolderId, folderHierarchy, folderIds, folderNames, folderPath]);

  const chosenFolderId =
    uploadFolderPath.length > 0 ? uploadFolderPath[uploadFolderPath.length - 1].id
    : uploadRootFolderId && folderIds.includes(uploadRootFolderId) ? uploadRootFolderId
    : (folderIds[0] ?? '');

  const canSelectUpload = !loading && !isBusy && Boolean(chosenFolderId);
  useEffect(() => { setUploadCandidates(null); }, [uploadFile, chosenFolderId]);

  // ===================================================
  // 9. 파일 검색 및 선택
  // ===================================================

  // 폴더별 파일 조회
  const [folderFiles, setFolderFiles] = useState<DriveFile[] | null>(null);
  const [folderFilesLoading, setFolderFilesLoading] = useState(false);

  const currentBrowseFolderId = folderPath.length > 0
    ? folderPath[folderPath.length - 1].id
    : activeFolderId;

  useEffect(() => {
    if (!workspaceId || !activeFolderId) {
      setFolderFiles(null);
      setSubfolders([]);
      setDirectFiles([]);
      return;
    }
    let active = true;
    const browseFolderId = currentBrowseFolderId ?? activeFolderId;
    const isSubfolder = folderPath.length > 0 && !folderIds.includes(browseFolderId);
    setFolderFilesLoading(true);
    setSubfoldersLoading(true);
    Promise.allSettled([
      isSubfolder
        ? getDriveDirectFiles(workspaceId, browseFolderId)
        : getDriveFiles(workspaceId, browseFolderId),
      getDriveSubfolders(workspaceId, browseFolderId),
    ]).then(([filesResult, subfoldersResult]) => {
      if (!active) return;
      if (isSubfolder) {
        setFolderFiles(null);
        if (filesResult.status === 'fulfilled') setDirectFiles(filesResult.value as DriveDirectFile[]);
        else setDirectFiles([]);
      } else {
        setDirectFiles([]);
        if (filesResult.status === 'fulfilled') setFolderFiles(filesResult.value as DriveFile[]);
        else setFolderFiles(null);
      }
      if (subfoldersResult.status === 'fulfilled') setSubfolders(subfoldersResult.value);
      else setSubfolders([]);
    }).finally(() => {
      if (!active) return;
      setFolderFilesLoading(false);
      setSubfoldersLoading(false);
    });
    return () => { active = false; };
  }, [workspaceId, activeFolderId, currentBrowseFolderId, files, folderPath.length, folderIdsKey]);

  function navigateToSubfolder(subfolder: DriveSubfolder) {
    setFolderPath(prev => [...prev, { id: subfolder.id, name: subfolder.name ?? subfolder.id }]);
    setSelectedId(null);
    setQuery('');
  }

  function selectBrowseFolder(rootId: string | null, path: DriveFolderPathEntry[]) {
    setActiveFolderId(rootId);
    setFolderPath(path);
    setSelectedId(null);
    setQuery('');
  }

  function navigateToPathIndex(index: number) {
    if (index < 0) {
      setFolderPath([]);
    } else {
      setFolderPath(prev => prev.slice(0, index + 1));
    }
    setSelectedId(null);
    setQuery('');
  }

  const isInSubfolder = folderPath.length > 0 && activeFolderId != null && !folderIds.includes(currentBrowseFolderId!);

  const currentFiles = showTrash || isInSubfolder
    ? []
    : (activeFolderId && folderFiles != null ? folderFiles : files);

  const visibleTrash = useMemo(() => {
    if (!showTrash) return [];
    const keyword = query.trim().toLowerCase();
    if (!keyword) return trashFiles;
    return trashFiles.filter(item => (item.name || '').toLowerCase().includes(keyword));
  }, [trashFiles, query, showTrash]);

  const visibleFiles = useMemo(() => {
    const keyword = query.trim().toLowerCase();

    if (!keyword) return currentFiles;

    return currentFiles.filter(file =>
      (file.title || '')
        .toLowerCase()
        .includes(keyword),
    );
  }, [currentFiles, query]);

  const visibleDirectFiles = useMemo(() => {
    if (!isInSubfolder) return [];
    const keyword = query.trim().toLowerCase();
    if (!keyword) return directFiles;
    return directFiles.filter(f =>
      (f.name || '').toLowerCase().includes(keyword),
    );
  }, [directFiles, query, isInSubfolder]);

  const visibleSubfolders = useMemo(() => {
    if (showTrash || !activeFolderId) return [];
    const keyword = query.trim().toLowerCase();
    if (!keyword) return subfolders;
    return subfolders.filter(f =>
      (f.name || '').toLowerCase().includes(keyword),
    );
  }, [subfolders, query, showTrash, activeFolderId]);

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

  function handleDragEnter(event: globalThis.DragEvent) {
    if (!event.dataTransfer?.types.includes('Files')) return;
    event.preventDefault();
    dragDepthRef.current += 1;
    if (canSelectUpload) setIsDraggingFile(true);
  }

  function handleDragOver(event: globalThis.DragEvent) {
    if (!event.dataTransfer?.types.includes('Files')) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = canSelectUpload ? 'copy' : 'none';
  }

  function handleDragLeave() {
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) setIsDraggingFile(false);
  }

  function handleFileDrop(event: globalThis.DragEvent) {
    if (!event.dataTransfer?.types.includes('Files')) return;
    event.preventDefault();
    dragDepthRef.current = 0;
    setIsDraggingFile(false);
    if (!canSelectUpload || workingRef.current) return;

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

  // 화면 어디에 끌어다 놓아도 업로드할 파일로 고른다. (업로드 칸뿐 아니라 사이드바·목록 위에서도)
  const dragHandlersRef = useRef({ handleDragEnter, handleDragOver, handleDragLeave, handleFileDrop });
  dragHandlersRef.current = { handleDragEnter, handleDragOver, handleDragLeave, handleFileDrop };
  useEffect(() => {
    const enter = (e: globalThis.DragEvent) => dragHandlersRef.current.handleDragEnter(e);
    const over = (e: globalThis.DragEvent) => dragHandlersRef.current.handleDragOver(e);
    const leave = () => dragHandlersRef.current.handleDragLeave();
    const drop = (e: globalThis.DragEvent) => dragHandlersRef.current.handleFileDrop(e);
    window.addEventListener('dragenter', enter);
    window.addEventListener('dragover', over);
    window.addEventListener('dragleave', leave);
    window.addEventListener('drop', drop);
    return () => {
      window.removeEventListener('dragenter', enter);
      window.removeEventListener('dragover', over);
      window.removeEventListener('dragleave', leave);
      window.removeEventListener('drop', drop);
    };
  }, []);

  // ===================================================
  // 13. Google Drive 파일 업로드
  // ===================================================

  async function refreshUploadCandidates() {
    if (!uploadFile || !chosenFolderId || workingRef.current) return;
    workingRef.current = true;
    setWorking('check-upload');
    setError(null);
    try {
      setUploadCandidates(await getDriveUploadCandidates(workspaceId, chosenFolderId, uploadFile.name));
    } catch (reason) { setError(errorMessage(reason)); }
    finally { workingRef.current = false; setWorking(null); }
  }

  async function handleUpload(options: DriveUploadOptions = { mode: 'ASK' }) {
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

    setWorking(options.mode === 'ASK' ? 'check-upload' : 'upload');
    setError(null);
    setNotice(null);

    const fileName = uploadFile.name;

    try {
      if (options.mode === 'ASK') {
        const matches = await getDriveUploadCandidates(workspaceId, chosenFolderId, fileName);
        if (matches.length > 0) { setUploadCandidates(matches); return; }
      }
      setWorking('upload');
      const uploaded = await uploadDriveFile(
        workspaceId,
        chosenFolderId,
        uploadFile,
        options,
      );

      // 연동 폴더 바로 아래에 올린 파일은 바로 목록에 표시한다.
      // 하위 폴더에 올린 파일은 수집 목록에 없으므로(ID 없음) 목록을 다시 불러와 하위 폴더에서 보인다.
      if (uploaded.contentRefId != null) {
        setFiles(current => [
          uploaded,
          ...current.filter(
            file =>
              file.contentRefId !==
              uploaded.contentRefId,
          ),
        ]);
        setSelectedId(uploaded.contentRefId);
      }

      setShowTrash(false);
      setQuery('');

      setUploadFile(null);
      setUploadCandidates(null);

      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }

      setNotice(
        `'${fileName}' 파일 ${options.mode === 'OVERWRITE' ? '덮어쓰기' : '업로드'}가 완료됐습니다.`,
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
      if (err instanceof ApiError && (err.code === 'DRIVE_FILE_NAME_CONFLICT' || err.code === 'DRIVE_FILE_CHANGED')) {
        try {
          setUploadCandidates(await getDriveUploadCandidates(workspaceId, chosenFolderId, fileName));
        } catch { /* 원래 오류와 선택 파일을 유지하고 사용자가 다시 확인할 수 있게 한다. */ }
      }
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
      `'${file.title || '(제목 없음)'}' 파일을 휴지통으로 이동할까요?\n\n팀장 Drive의 ToGather 휴지통 폴더로 옮겨지며, 휴지통에서 복원할 수 있습니다.`,
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

  // ===================================================
  // 새 폴더 만들기: 지금 보고 있는 폴더 안에 만든다.
  // ===================================================

  /** 새 폴더를 만들 곳. "전체 파일"을 보고 있으면 연결된 폴더가 하나일 때만 그 폴더 */
  const createFolderParentId = showTrash
    ? null
    : currentBrowseFolderId ?? (folderIds.length === 1 ? folderIds[0] : null);
  const createFolderParentName = createFolderParentId
    ? (folderPath.length > 0 ? folderPath[folderPath.length - 1].name : null)
      ?? folderNames[createFolderParentId] ?? '현재 폴더'
    : null;

  async function handleCreateFolder() {
    const name = (newFolderName ?? '').trim();
    if (!workspaceId || !createFolderParentId || !name) return;
    await runTrashAction('create-folder', null, async () => {
      const folder = await createDriveFolder(workspaceId, createFolderParentId, name);
      setNewFolderName(null);
      setFolderHierarchyVersion((value) => value + 1);
      return `'${folder.name ?? name}' 폴더를 만들었습니다.`;
    });
  }

  /** 휴지통 동작 공통: 진행 표시 → 실행 → 목록 다시 불러오기 */
  async function runTrashAction(
    action: WorkingAction,
    itemId: string | null,
    run: () => Promise<string>,
  ) {
    if (!workspaceId || workingRef.current) return;

    workingRef.current = true;
    dataVersionRef.current += 1;
    setWorking(action);
    setWorkingItemId(itemId);
    setError(null);
    setNotice(null);

    try {
      const message = await run();
      setNotice(message);
      try {
        await reloadAll();
      } catch {
        setNotice(`${message} 목록 갱신에는 실패했습니다. 새로고침해 주세요.`);
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      workingRef.current = false;
      setWorking(null);
      setWorkingItemId(null);
    }
  }

  /** 하위 폴더나 하위 폴더 안의 파일을 휴지통으로 */
  function handleTrashItem(itemId: string, name: string | null, folder: boolean) {
    const label = name || (folder ? '폴더' : '파일');
    if (!window.confirm(
      `'${label}' ${folder ? '폴더(안의 파일 포함)를' : '파일을'} 휴지통으로 이동할까요?\n\n팀장 Drive의 ToGather 휴지통 폴더로 옮겨지며, 휴지통에서 복원할 수 있습니다.`,
    )) return;
    void runTrashAction('delete', itemId, async () => {
      await trashDriveItem(workspaceId, itemId);
      return `'${label}'을 휴지통으로 이동했습니다.`;
    });
  }

  function handleRestore(item: DriveTrashItem) {
    const label = item.name || '항목';
    void runTrashAction('restore', item.itemId, async () => {
      // Drive 웹에서 Google Drive 휴지통으로 보낸 수집 파일은 수집 ID로 되살린다.
      if (item.source === 'DRIVE' && item.contentRefId != null) {
        await restoreDriveFile(workspaceId, item.contentRefId);
      } else {
        await restoreDriveTrashItem(workspaceId, item.itemId);
      }
      return `'${label}' 복원이 완료됐습니다.`;
    });
  }

  function handleDeleteForever(item: DriveTrashItem) {
    const label = item.name || '항목';
    if (!window.confirm(
      `'${label}'${item.folder ? ' 폴더와 안의 모든 파일' : ''}을 영구 삭제할까요?\n\n팀장 Drive에서 완전히 지워지며 되돌릴 수 없습니다.`,
    )) return;
    void runTrashAction('delete', item.itemId, async () => {
      await deleteDriveTrashItem(workspaceId, item.itemId);
      return `'${label}'을 영구 삭제했습니다.`;
    });
  }

  function handleEmptyTrash() {
    if (!window.confirm(
      '휴지통의 모든 항목을 영구 삭제할까요?\n\n팀장 Drive에서 완전히 지워지며 되돌릴 수 없습니다.',
    )) return;
    void runTrashAction('empty', null, async () => {
      const result = await emptyDriveTrash(workspaceId);
      return result.failed > 0
        ? `${result.deleted}개를 영구 삭제했고, ${result.failed}개는 지우지 못했습니다.`
        : `휴지통을 비웠습니다. (${result.deleted}개 영구 삭제)`;
    });
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
      {/* 화면 어디로 끌어와도 점선 테두리로 놓을 수 있다고 알린다 */}
      {isDraggingFile && canSelectUpload && (
        <div className="drive-drop-overlay" aria-hidden="true">
          <div className="drive-drop-overlay-label">
            <span className="text-3xl">☁️</span>
            <strong>여기에 놓으면 업로드할 파일로 선택돼요</strong>
            <span className="text-[13px] text-[var(--muted)]">파일 한 개, 최대 100MB</span>
          </div>
        </div>
      )}
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

      {/* 팀 Drive는 팀장 소유: 팀장 연동 · 팀장 넘기기 인계 · 팀장 소유가 아닌 항목 안내 */}
      {folderIds.length > 0 && ownership && !ownership.ownerConnected && (
        <div role="status" className="alert">
          팀장({ownership.ownerName ?? '팀장'} 님)이 Google Drive를 연동해야 휴지통을 쓸 수 있고, 올린 파일이 팀장 소유로 저장돼요.
        </div>
      )}
      {ownership?.transfer?.status === 'RUNNING' && (
        <div role="status" className="alert">
          팀장이 바뀌어 Drive 파일 소유권을 {ownership.transfer.fromName ?? '이전 팀장'} 님에서{' '}
          {ownership.transfer.toName ?? '새 팀장'} 님에게 넘기는 중이에요. ({ownership.transfer.doneCount}개 완료
          {ownership.transfer.remainingCount > 0 && `, ${ownership.transfer.remainingCount}개 남음`})
          {ownership.transfer.lastError && ` · ${ownership.transfer.lastError}`}
        </div>
      )}
      {folderIds.length > 0 && ownership && ownership.pendingOwnershipCount > 0
        && ownership.transfer?.status !== 'RUNNING' && (
        <div role="status" className="alert">
          팀장 소유가 아닌 항목이 {ownership.pendingOwnershipCount}개 있어요. Drive에서 직접 올린 파일로, 올린 사람이
          ToGather에 Drive를 연동하면 자동으로 팀장에게 넘어가요.
        </div>
      )}

      {/* 업로드 줄 */}
      <div
        role="region"
        aria-label="Google Drive 파일 업로드 영역"
        aria-busy={working === 'upload' || working === 'check-upload'}
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
            <DriveFolderPicker
              workspaceId={workspaceId}
              folders={browseRootFolders}
              connectedCount={folderIds.length}
              activeFolderId={uploadRootFolderId ?? (folderIds[0] || null)}
              path={uploadFolderPath}
              disabled={isBusy || folderHierarchyLoading}
              showAll={false}
              label="업로드할 폴더"
              onChange={(rootId, path) => {
                setUploadRootFolderId(rootId);
                setUploadFolderPath(path);
              }}
            />
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
              {working === 'upload' ? '업로드 중...' : working === 'check-upload' ? '파일 확인 중...' : '업로드'}
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

      {uploadFile && uploadCandidates && <DriveUploadConflict
        key={uploadCandidates.map((candidate) => `${candidate.fileId}/${candidate.version}`).join('|')}
        fileName={uploadFile.name} candidates={uploadCandidates} busy={isBusy}
        onCreateNew={() => void handleUpload({ mode: 'CREATE_NEW' })}
        onOverwrite={(candidate) => void handleUpload({ mode: 'OVERWRITE', targetFileId: candidate.fileId,
          expectedVersion: candidate.version! })}
        onCancel={() => setUploadCandidates(null)} onRefresh={() => void refreshUploadCandidates()} />}

      <div className="drive-board">
        {/* 파일 목록 */}
        <div className="card overflow-hidden !p-0">
          <div className="flex flex-wrap items-center justify-between gap-3 px-5 pt-5 pb-3">
            <div className="flex items-center gap-1.5">
              <strong className="text-[17px]">{showTrash ? 'Drive 휴지통' : '파일 목록'}</strong>
              <span className="badge">{showTrash ? trashFiles.length : isInSubfolder ? (directFiles.length + subfolders.length) : (activeFolderId && folderFiles ? folderFiles.length + subfolders.length : files.length)}</span>
            </div>
            <div className="flex items-center gap-2">
              {showTrash && isAdmin && trashFiles.some((item) => item.source === 'TOGATHER') && (
                <button
                  type="button"
                  className="btn btn-small btn-danger"
                  disabled={isBusy}
                  onClick={handleEmptyTrash}
                >
                  {working === 'empty' ? '비우는 중...' : '휴지통 비우기'}
                </button>
              )}
              {!showTrash && folderIds.length > 0 && (
                <button
                  type="button"
                  className="btn btn-small"
                  disabled={isBusy || !createFolderParentId}
                  title={createFolderParentId ? undefined : '연결된 폴더에서 폴더를 먼저 고르세요.'}
                  onClick={() => setNewFolderName((value) => (value === null ? '' : null))}
                >
                  + 새 폴더
                </button>
              )}
              <a
                href="https://drive.google.com/drive/my-drive"
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-small"
              >
                ↗ Drive에서 열기
              </a>
            </div>
          </div>

          {newFolderName !== null && createFolderParentId && !showTrash && (
            <form
              className="flex flex-wrap items-center gap-2 border-b border-[var(--border-soft)] px-5 pb-3"
              onSubmit={(event) => {
                event.preventDefault();
                void handleCreateFolder();
              }}
            >
              <span className="text-xs text-[var(--muted)]">'{createFolderParentName}' 안에</span>
              <input
                className="input !w-auto flex-1"
                aria-label="새 폴더 이름"
                placeholder="새 폴더 이름"
                maxLength={255}
                autoFocus
                value={newFolderName}
                disabled={isBusy}
                onChange={(event) => setNewFolderName(event.target.value)}
              />
              <button type="submit" className="btn btn-small btn-primary" disabled={isBusy || !newFolderName.trim()}>
                {working === 'create-folder' ? '만드는 중...' : '만들기'}
              </button>
              <button type="button" className="btn btn-small" disabled={isBusy} onClick={() => setNewFolderName(null)}>
                취소
              </button>
            </form>
          )}

          {/* 연결된 폴더 드롭다운 · 하위 폴더 탐색 */}
          {folderIds.length > 0 && !showTrash && (
            <div className="border-b border-[var(--border-soft)] px-5 pb-3">
              <DriveFolderPicker workspaceId={workspaceId}
                folders={browseRootFolders} connectedCount={folderIds.length}
                activeFolderId={activeFolderId} path={folderPath} disabled={loading || isBusy || folderHierarchyLoading || folderHierarchyError != null}
                onChange={selectBrowseFolder} />
              {folderHierarchyLoading && <span className="mt-2 block text-xs text-[var(--muted)]" role="status">폴더 구조 불러오는 중…</span>}
              {folderHierarchyError && <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-[var(--danger)]" role="status">
                <span>폴더 구조를 불러오지 못했어요.</span>
                <button type="button" className="btn btn-small" onClick={() => setFolderHierarchyVersion(value => value + 1)}>다시 불러오기</button>
              </div>}
            </div>
          )}

          {/* 현재 폴더 경로 (브레드크럼) */}
          {activeFolderId && !showTrash && <DriveFolderBreadcrumbs
            root={{ id: activeFolderId, name: folderNames[activeFolderId] ?? '연결된 폴더' }} path={folderPath}
            loading={folderFilesLoading || subfoldersLoading} disabled={isBusy}
            onAll={() => selectBrowseFolder(null, [])} onNavigate={navigateToPathIndex} />}

          <div className="flex flex-wrap items-center gap-2 border-b border-[var(--border-soft)] px-5 pb-4 pt-3">
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
                파일 ({isInSubfolder ? directFiles.length + subfolders.length : activeFolderId && folderFiles ? folderFiles.length + subfolders.length : files.length})
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

          <div className={`drive-row drive-head max-sm:grid-cols-[minmax(0,1fr)_auto]${showTrash ? ' drive-trash' : ''}`}>
            <span>{showTrash ? '이름 · 삭제한 사람' : '파일 이름 · 크기'}</span>
            <span className="max-sm:hidden">{showTrash ? '삭제한 때' : '최근 수정'}</span>
            <span>{showTrash ? '' : '연결'}</span>
          </div>

          {loading ? (
            <div className="py-12 text-center text-[13px] text-[var(--muted)]">Google Drive 파일을 불러오는 중입니다...</div>
          ) : (showTrash
            ? visibleTrash.length === 0
            : visibleFiles.length === 0 && visibleSubfolders.length === 0 && visibleDirectFiles.length === 0) ? (
            <div className="py-12 text-center text-[13px] text-[var(--muted)]">
              {query ? '검색 결과가 없습니다.' : showTrash ? '휴지통이 비어 있습니다.' : '동기화된 파일이 없습니다.'}
              {!query && !showTrash && (
                <div className="mt-2">
                  <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리에서 Drive 폴더 확인하기 →</Link>
                </div>
              )}
            </div>
          ) : (
            <>
            {showTrash && visibleTrash.map((item) => {
              const format = itemFormat(item.name, item.mimeType);
              const isWorkingItem = workingItemId === item.itemId;
              return (
                <div
                  key={`${item.source}-${item.itemId}`}
                  className="drive-row drive-item drive-trash max-sm:grid-cols-[minmax(0,1fr)_auto] max-sm:gap-y-1"
                >
                  <span className="flex min-w-0 items-center gap-3">
                    {item.folder
                      ? <FolderGlyph />
                      : <span className={`drive-ext ext-${format.toLowerCase()}`}>{format.slice(0, 4)}</span>}
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[15px] font-bold">{item.name || '(이름 없음)'}</span>
                      <span className="block truncate text-xs text-[var(--muted)]">
                        {item.folder ? '폴더' : formatDriveFileSize(item.fileSize)}
                        {item.trashedByName && ` · ${item.trashedByName} 님이 삭제`}
                        {item.source === 'DRIVE' && ' · Google Drive 휴지통'}
                      </span>
                    </span>
                  </span>
                  <span className="text-sm max-sm:col-span-2 max-sm:row-start-2 max-sm:pl-[50px]">{formatDate(item.trashedAt)}</span>
                  <span className="flex flex-wrap justify-end gap-1.5 max-sm:col-start-2 max-sm:row-start-1">
                    <button type="button" className="btn btn-small" disabled={isBusy} onClick={() => handleRestore(item)}>
                      {isWorkingItem && working === 'restore' ? '복원 중...' : '↩ 복원'}
                    </button>
                    {isAdmin && (
                      <button
                        type="button"
                        className="btn btn-small btn-danger"
                        disabled={isBusy}
                        onClick={() => handleDeleteForever(item)}
                      >
                        {isWorkingItem && working === 'delete' ? '삭제 중...' : '영구 삭제'}
                      </button>
                    )}
                  </span>
                </div>
              );
            })}
            {visibleSubfolders.map((folder) => (
              <div
                key={`folder-${folder.id}`}
                className="drive-row drive-item drive-subfolder-row max-sm:grid-cols-[minmax(0,1fr)_auto] max-sm:gap-y-1"
              >
                <button
                  type="button"
                  onClick={() => navigateToSubfolder(folder)}
                  disabled={isBusy}
                  className="drive-row-main"
                  aria-label={`${folder.name || '폴더'} 열기`}
                >
                  <FolderGlyph />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[15px] font-bold">{folder.name || '(이름 없음)'}</span>
                    <span className="block text-xs text-[var(--muted)]">폴더 ▸</span>
                  </span>
                </button>
                <span className="text-sm max-sm:col-span-2 max-sm:row-start-2 max-sm:pl-[50px]" />
                <span className="flex justify-end max-sm:col-start-2 max-sm:row-start-1">
                  <button
                    type="button"
                    className="btn btn-small"
                    disabled={isBusy}
                    onClick={() => handleTrashItem(folder.id, folder.name, true)}
                    title="폴더를 휴지통으로 이동"
                  >
                    {workingItemId === folder.id ? '이동 중...' : '🗑'}
                  </button>
                </span>
              </div>
            ))}
            {!isInSubfolder && visibleFiles.map((file) => {
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
                  className={`drive-row drive-item max-sm:grid-cols-[minmax(0,1fr)_auto] max-sm:gap-y-1${selected ? ' selected' : ''}`}
                >
                  <span className="flex min-w-0 items-center gap-3">
                    <span className={`drive-ext ext-${format.toLowerCase()}`}>{format.slice(0, 4)}</span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[15px] font-bold">{file.title || '(제목 없음)'}</span>
                      <span className="block text-xs text-[var(--muted)]">{formatDriveFileSize(file.fileSize)}</span>
                      {file.syncFailed && <span className="text-xs text-[var(--warning)]">동기화 실패</span>}
                    </span>
                  </span>
                  <span className="text-sm max-sm:col-span-2 max-sm:row-start-2 max-sm:pl-[50px]">{formatDate(file.lastModifiedAt)}</span>
                  <span className="text-xs font-bold text-[var(--primary)] max-sm:col-start-2 max-sm:row-start-1">
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
            })}
            {isInSubfolder && visibleDirectFiles.map((file) => {
              const format = itemFormat(file.name, file.mimeType);
              return (
                <div
                  key={file.id}
                  className="drive-row drive-item max-sm:grid-cols-[minmax(0,1fr)_auto] max-sm:gap-y-1"
                >
                  <a
                    href={file.webViewLink || `https://drive.google.com/file/d/${file.id}/view`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="drive-row-main"
                  >
                    <span className={`drive-ext ext-${format.toLowerCase()}`}>{format.slice(0, 4)}</span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[15px] font-bold">{file.name || '(제목 없음)'}</span>
                      <span className="block text-xs text-[var(--muted)]">{formatDriveFileSize(file.fileSize)} · Drive에서 열기 ↗</span>
                    </span>
                  </a>
                  <span className="text-sm max-sm:col-span-2 max-sm:row-start-2 max-sm:pl-[50px]">{formatDate(file.modifiedAt)}</span>
                  <span className="flex justify-end max-sm:col-start-2 max-sm:row-start-1">
                    <button
                      type="button"
                      className="btn btn-small"
                      disabled={isBusy}
                      onClick={() => handleTrashItem(file.id, file.name, false)}
                      title="파일을 휴지통으로 이동"
                    >
                      {workingItemId === file.id ? '이동 중...' : '🗑'}
                    </button>
                  </span>
                </div>
              );
            })}
            </>
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
                    {fileFormat(selectedFile)} · {formatDriveFileSize(selectedFile.fileSize)} · 수정 {formatDate(selectedFile.lastModifiedAt)}
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
                <button
                  type="button"
                  disabled={isBusy}
                  onClick={() => void handleDelete(selectedFile)}
                  className="btn btn-danger !h-11 w-full"
                  title="팀장 Drive의 ToGather 휴지통 폴더로 옮기며, 영구 삭제되지 않습니다."
                >
                  {working === 'delete' ? '삭제 중...' : '🗑 휴지통으로 이동'}
                </button>
              </div>
            </>
          ) : showTrash ? (
            <div className="flex flex-col gap-2 py-6 text-[13px] leading-relaxed text-[var(--muted)]">
              <p className="m-0">
                휴지통으로 옮긴 파일·폴더는 팀장 Drive의 <strong className="text-[var(--text)]">ToGather 휴지통</strong> 폴더에
                모여 팀원에게서 사라집니다.
              </p>
              <p className="m-0">복원하면 원래 폴더로 돌아가고, 원래 폴더가 없어졌으면 연동 폴더 바로 아래로 돌아갑니다.</p>
              <p className="m-0">영구 삭제와 휴지통 비우기는 팀장·관리자만 할 수 있어요.</p>
            </div>
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
