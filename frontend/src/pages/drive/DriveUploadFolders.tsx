// 파일을 올릴 Drive 폴더 고르기. 산출물 제출·Slack 파일 첨부에서 함께 쓴다.
// 연동 관리에서 고른 폴더(하위 폴더 포함)만 보여 준다. 그 밖의 폴더에 올린 파일은 수집되지 않는다.
import { useEffect, useMemo, useState } from 'react';
import { getDriveFolderHierarchy, type DriveFolderHierarchy } from './api';

const LAST_FOLDER_KEY = (workspaceId: string) => `togather.uploadFolder.${workspaceId}`;

export interface DriveFolderRow {
  id: string;
  name: string;
  depth: number;
  /** 예: "ToGather 최종발표 / 발표자료" */
  path: string;
}

/** 연동 폴더를 경로순으로 정렬하고, 위 폴더 아래에 들여 쓴다. */
function toRows(hierarchy: DriveFolderHierarchy[]): DriveFolderRow[] {
  return hierarchy
    .map((folder) => {
      const names = [...folder.ancestors.map((a) => a.name ?? '이름 없음'), folder.name ?? '이름 없음'];
      return { id: folder.folderId, name: names[names.length - 1], depth: folder.ancestors.length, path: names.join(' / ') };
    })
    .sort((a, b) => a.path.localeCompare(b.path, 'ko'));
}

/** 올릴 수 있는 폴더 목록과 고른 폴더. 마지막으로 쓴 폴더를 먼저 골라 둔다. */
export function useDriveUploadFolders(workspaceId: string, enabled = true) {
  const [rows, setRows] = useState<DriveFolderRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [folderId, setFolderId] = useState('');

  useEffect(() => {
    if (!enabled) return;
    let active = true;
    getDriveFolderHierarchy(workspaceId)
      .then((hierarchy) => {
        if (!active) return;
        const list = toRows(hierarchy);
        setRows(list);
        const last = localStorage.getItem(LAST_FOLDER_KEY(workspaceId));
        setFolderId(list.find((row) => row.id === last)?.id ?? list[0]?.id ?? '');
      })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : 'Drive 폴더를 불러오지 못했습니다.');
      });
    return () => { active = false; };
  }, [workspaceId, enabled]);

  const selected = useMemo(() => rows?.find((row) => row.id === folderId) ?? null, [rows, folderId]);
  /** 실제로 올린 뒤 부른다. 다음에 이 폴더를 먼저 골라 둔다. */
  const remember = () => { if (folderId) localStorage.setItem(LAST_FOLDER_KEY(workspaceId), folderId); };
  return { rows, error, folderId, setFolderId, selected, remember };
}

export function DriveFolderList({ rows, value, onChange, disabled = false }: {
  rows: DriveFolderRow[];
  value: string;
  onChange: (folderId: string) => void;
  disabled?: boolean;
}) {
  return (
    <div role="radiogroup" aria-label="저장할 폴더" className="max-h-56 overflow-y-auto rounded-xl border border-[var(--border)] p-1.5">
      {rows.map((row) => {
        const active = row.id === value;
        return (
          <button key={row.id} type="button" role="radio" aria-checked={active} disabled={disabled}
            onClick={() => onChange(row.id)} title={row.path}
            className={`flex w-full cursor-pointer items-center gap-2 rounded-lg border-0 py-2 pr-3 text-left text-[13px] transition disabled:cursor-not-allowed ${
              active ? 'bg-[var(--primary-soft)] font-bold text-[var(--primary)]' : 'bg-transparent text-[var(--text)] hover:bg-[var(--surface-2)]'
            }`}
            style={{ paddingLeft: 10 + row.depth * 18 }}>
            {row.depth > 0 && <span className="text-[var(--muted)]">└</span>}
            <span aria-hidden="true">📁</span>
            <span className="min-w-0 flex-1 truncate">{row.name}</span>
            {active && <span className="text-xs">✓</span>}
          </button>
        );
      })}
    </div>
  );
}

export function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}
