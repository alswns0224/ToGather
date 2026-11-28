// 필수 산출물 제출: 저장할 Drive 폴더를 고르고 파일을 올린 뒤 제출한다. (팀 작업 상세·목록의 "완료"에서 함께 쓴다)
// 폴더는 연동 관리에서 고른 폴더(하위 폴더 포함)만 보여 준다. 그 밖의 폴더에 올린 파일은 수집되지 않아 제출할 수 없다.
import { useEffect, useRef, useState, type DragEvent } from 'react';
import { Link } from 'react-router';
import { uploadDriveFile, MAX_DRIVE_UPLOAD_SIZE } from '../drive/api';
import { DriveFolderList, formatFileSize, useDriveUploadFolders } from '../drive/DriveUploadFolders';
import NavIcon from '../../layouts/NavIcon';
import { submitTaskDeliverable, type TaskDeliverable } from './api';

export default function DeliverableSubmitDialog({ workspaceId, taskId, deliverable, onClose, onSubmitted }: {
  workspaceId: string;
  taskId: string;
  deliverable: TaskDeliverable;
  onClose: () => void;
  onSubmitted: () => void;
}) {
  const { rows: folders, error: folderError, folderId, setFolderId, selected, remember } = useDriveUploadFolders(workspaceId);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 올리기는 됐는데 제출이 실패하면 다시 올리지 않고 제출만 다시 한다.
  const [uploaded, setUploaded] = useState<{ key: string; contentRefId: number } | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const extension = deliverable.ext?.trim().replace(/^\./, '').toLowerCase() || null;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [busy, onClose]);

  function choose(chosen: File | null) {
    setError(null);
    setUploaded(null);
    if (!chosen) { setFile(null); return; }
    if (extension && !chosen.name.toLowerCase().endsWith(`.${extension}`)) {
      setError(`.${extension} 파일만 제출할 수 있어요.`);
      return;
    }
    if (chosen.size === 0) { setError('빈 파일은 올릴 수 없어요.'); return; }
    if (chosen.size > MAX_DRIVE_UPLOAD_SIZE) { setError('100MB 이하 파일만 올릴 수 있어요.'); return; }
    setFile(chosen);
  }

  function onDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragging(false);
    if (!busy) choose(e.dataTransfer.files?.[0] ?? null);
  }

  async function submit() {
    if (!file || !folderId || busy) return;
    setBusy(true);
    setError(null);
    const key = `${folderId}:${file.name}:${file.size}:${file.lastModified}`;
    try {
      let contentRefId = uploaded?.key === key ? uploaded.contentRefId : null;
      if (contentRefId === null) {
        const result = await uploadDriveFile(workspaceId, folderId, file);
        if (result.contentRefId == null) throw new Error('올린 파일을 확인하지 못했어요. 연동 폴더를 다시 골라 주세요.');
        contentRefId = result.contentRefId;
        setUploaded({ key, contentRefId });
      }
      await submitTaskDeliverable(workspaceId, taskId, deliverable.deliverableId, contentRefId);
      remember();
      onSubmitted();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '제출하지 못했어요.');
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onClick={() => !busy && onClose()}>
      <div role="dialog" aria-modal="true" aria-label={`${deliverable.name} 제출`}
        className="flex max-h-[calc(100dvh-32px)] w-full max-w-[560px] flex-col overflow-hidden rounded-[22px] border border-[var(--border)] bg-[var(--surface)] shadow-[var(--shadow)]"
        onClick={(e) => e.stopPropagation()}>
        <header className="flex items-start justify-between gap-3 border-b border-[var(--border-soft)] px-5 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-[var(--primary-soft)] text-[var(--primary)]">
              <NavIcon name="drive" className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <h2 className="m-0 truncate text-[17px] font-extrabold">산출물 제출 · {deliverable.name}</h2>
              <p className="m-0 mt-0.5 text-xs text-[var(--muted)]">
                고른 Drive 폴더에 올린 뒤 이 항목으로 제출해요.{extension ? ` (.${extension} 파일만)` : ''}
              </p>
            </div>
          </div>
          <button type="button" className="btn btn-small" onClick={onClose} disabled={busy} aria-label="닫기">✕</button>
        </header>

        <div className="flex min-h-0 flex-col gap-5 overflow-y-auto px-5 py-5">
          <section>
            <div className="mb-2 flex items-center gap-2">
              <span className="flex h-6 w-6 items-center justify-center rounded-lg bg-[var(--primary-soft)] text-[11px] font-bold text-[var(--primary)]">01</span>
              <strong className="text-sm">저장할 폴더</strong>
            </div>
            {folderError && <p role="alert" className="m-0 text-sm text-[var(--danger)]">{folderError}</p>}
            {!folders && !folderError && <p className="m-0 text-sm text-[var(--muted)]">폴더를 불러오는 중...</p>}
            {folders?.length === 0 && (
              <p className="m-0 text-sm text-[var(--muted)]">
                연동된 Drive 폴더가 없어요. <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리</Link>에서 폴더를 골라 주세요.
              </p>
            )}
            {folders && folders.length > 0 && (
              <DriveFolderList rows={folders} value={folderId} disabled={busy}
                onChange={(id) => { setFolderId(id); setUploaded(null); }} />
            )}
            {selected && <p className="m-0 mt-1.5 text-[11px] text-[var(--muted)]">저장 위치: {selected.path}</p>}
          </section>

          <section>
            <div className="mb-2 flex items-center gap-2">
              <span className="flex h-6 w-6 items-center justify-center rounded-lg bg-[var(--primary-soft)] text-[11px] font-bold text-[var(--primary)]">02</span>
              <strong className="text-sm">파일</strong>
            </div>
            <input ref={inputRef} type="file" className="hidden" aria-label={`${deliverable.name} 파일 선택`}
              accept={extension ? `.${extension}` : undefined} disabled={busy}
              onChange={(e) => { choose(e.target.files?.[0] ?? null); e.target.value = ''; }} />
            <div
              onDragOver={(e) => { e.preventDefault(); if (!busy) setDragging(true); }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
              className={`flex flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed px-4 py-6 text-center transition ${
                dragging ? 'border-[var(--primary)] bg-[var(--primary-soft)]' : 'border-[var(--border)] bg-[var(--surface-2)]'
              }`}>
              {file ? (
                <>
                  <strong className="max-w-full truncate text-sm">{file.name}</strong>
                  <span className="text-xs text-[var(--muted)]">{formatFileSize(file.size)}</span>
                  <button type="button" className="btn btn-small" disabled={busy} onClick={() => inputRef.current?.click()}>
                    다른 파일 고르기
                  </button>
                </>
              ) : (
                <>
                  <span className="text-sm text-[var(--muted)]">여기에 파일을 끌어 놓거나</span>
                  <button type="button" className="btn btn-small" disabled={busy} onClick={() => inputRef.current?.click()}>
                    파일 고르기
                  </button>
                </>
              )}
            </div>
          </section>

          {error && <p role="alert" className="alert alert-error m-0">{error}</p>}
        </div>

        <footer className="flex justify-end gap-2 border-t border-[var(--border-soft)] px-5 py-4">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>취소</button>
          <button type="button" className="btn btn-primary" disabled={busy || !file || !folderId} onClick={() => void submit()}>
            {busy ? '올리는 중...' : uploaded ? '다시 제출' : 'Drive에 올리고 제출'}
          </button>
        </footer>
      </div>
    </div>
  );
}
