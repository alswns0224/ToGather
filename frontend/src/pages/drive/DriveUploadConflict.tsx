// UI-06 Drive 동명 파일 저장 방식 선택 (담당: C)
import { useState } from 'react';
import { formatDriveFileSize, type DriveUploadCandidate } from './api';

export default function DriveUploadConflict({ fileName, candidates, busy, onCreateNew, onOverwrite, onCancel, onRefresh }: {
  fileName: string;
  candidates: DriveUploadCandidate[];
  busy: boolean;
  onCreateNew: () => void;
  onOverwrite: (candidate: DriveUploadCandidate) => void;
  onCancel: () => void;
  onRefresh: () => void;
}) {
  const [selectedId, setSelectedId] = useState('');
  const selected = candidates.find((candidate) => candidate.fileId === selectedId && candidate.overwriteAllowed);
  return (
    <section className="card flex min-w-0 flex-col gap-3 border border-[var(--warning)]" aria-label="동명 파일 저장 방식">
      <strong className="break-all text-[17px]">‘{fileName}’ 저장 방식 선택</strong>
      <p className="m-0 text-sm text-[var(--muted)]">
        같은 이름의 새 파일로 추가하거나, 아래에서 선택한 파일의 내용을 교체할 수 있어요. 덮어쓰면 기존 파일 링크와 Task 연결은 유지됩니다.
      </p>
      {candidates.length === 0 && <p className="m-0 text-sm">현재 같은 이름의 파일이 없습니다. 새 파일로 저장할 수 있어요.</p>}
      <fieldset disabled={busy} className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
        <legend className="mb-2 text-sm font-bold">덮어쓸 파일 선택</legend>
        {candidates.map((candidate, index) => (
          <label key={candidate.fileId} className="flex min-w-0 items-start gap-3 rounded-xl bg-[var(--surface-2)] p-3 text-sm">
            <input className="mt-1" type="radio" name="drive-overwrite-target" value={candidate.fileId}
              checked={selectedId === candidate.fileId} disabled={!candidate.overwriteAllowed}
              onChange={() => setSelectedId(candidate.fileId)} />
            <span className="min-w-0 flex-1">
              <strong className="break-all">파일 {index + 1} · {candidate.name}</strong>
              <span className="mt-1 block text-xs text-[var(--muted)]">
                {formatDriveFileSize(candidate.fileSize)} · 수정 {candidate.lastModifiedAt?.replace('T', ' ').slice(0, 16) ?? '정보 없음'}
              </span>
              {!candidate.overwriteAllowed && <span className="mt-1 block text-xs text-[var(--warning)]">내용 교체 불가 · 새 파일로 저장해 주세요.</span>}
            </span>
            {candidate.deepLink && /^https?:\/\//i.test(candidate.deepLink) && (
              <a className="text-xs" href={candidate.deepLink} target="_blank" rel="noreferrer">원본 보기 ↗</a>
            )}
          </label>
        ))}
      </fieldset>
      <p className="m-0 text-xs text-[var(--muted)]">Google 문서·바로가기 또는 수정 권한이 없는 파일은 여기서 덮어쓰지 않습니다.</p>
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn btn-primary" disabled={busy} onClick={onCreateNew}>새 파일로 저장</button>
        <button type="button" className="btn btn-danger" disabled={busy || !selected}
          onClick={() => selected && onOverwrite(selected)}>선택한 파일 덮어쓰기</button>
        <button type="button" className="btn" disabled={busy} onClick={onRefresh}>다시 확인</button>
        <button type="button" className="btn" disabled={busy} onClick={onCancel}>취소</button>
      </div>
    </section>
  );
}
