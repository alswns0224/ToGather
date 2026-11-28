// 팀 작업 목록의 "완료": 필수 산출물이 있는 작업은 여기서 남은 산출물을 제출한다. 모두 제출하면 자동으로 완료된다.
import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import NavIcon from '../../layouts/NavIcon';
import DeliverableSubmitDialog from './DeliverableSubmitDialog';
import { getTaskContext, type TaskContext, type TaskDeliverable } from './api';

export default function TaskCompleteDialog({ workspaceId, taskId, onClose }: {
  workspaceId: string;
  taskId: number;
  /** 닫을 때 목록을 다시 불러온다. */
  onClose: () => void;
}) {
  const [context, setContext] = useState<TaskContext | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState<TaskDeliverable | null>(null);

  const load = useCallback(() => {
    setError(null);
    getTaskContext(workspaceId, String(taskId))
      .then(setContext)
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : '팀 작업을 불러오지 못했습니다.'));
  }, [workspaceId, taskId]);

  useEffect(load, [load]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !submitting) onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [submitting, onClose]);

  const deliverables = context?.deliverables ?? [];
  const submittedCount = deliverables.filter((d) => d.submitted).length;
  const done = context?.task.status === 'DONE';

  return (
    <div className="modal-backdrop" onClick={() => !submitting && onClose()}>
      <div role="dialog" aria-modal="true" aria-label="산출물 제출하고 완료"
        className="flex max-h-[calc(100dvh-32px)] w-full max-w-[520px] flex-col overflow-hidden rounded-[22px] border border-[var(--border)] bg-[var(--surface)] shadow-[var(--shadow)]"
        onClick={(e) => e.stopPropagation()}>
        <header className="flex items-start justify-between gap-3 border-b border-[var(--border-soft)] px-5 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-[var(--primary-soft)] text-[var(--primary)]">
              <NavIcon name="tasks" className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <h2 className="m-0 truncate text-[17px] font-extrabold">{context?.task.title ?? '팀 작업 완료'}</h2>
              <p className="m-0 mt-0.5 text-xs text-[var(--muted)]">필수 산출물을 모두 제출하면 자동으로 완료돼요.</p>
            </div>
          </div>
          <button type="button" className="btn btn-small" onClick={onClose} aria-label="닫기">✕</button>
        </header>

        <div className="flex min-h-0 flex-col gap-2.5 overflow-y-auto px-5 py-5">
          {error && <p role="alert" className="alert alert-error m-0">{error}</p>}
          {!context && !error && <p className="m-0 text-sm text-[var(--muted)]">불러오는 중...</p>}
          {context && (
            <>
              <div className="mb-1 flex items-center justify-between text-xs text-[var(--muted)]">
                <span>제출 {submittedCount}/{deliverables.length}</span>
                {done && <span className="badge badge-success">완료됨</span>}
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-[var(--surface-2)]">
                <div className="h-full rounded-full transition-all"
                  style={{ width: `${context.task.progress}%`, background: done ? 'var(--success)' : 'var(--brand-grad)' }} />
              </div>
              {deliverables.map((d) => {
                const ext = d.ext?.trim().replace(/^\./, '');
                return (
                  <div key={d.deliverableId}
                    className={`flex items-center justify-between gap-3 rounded-xl border px-3.5 py-3 ${
                      d.submitted ? 'border-transparent bg-[var(--success-soft)]' : 'border-[var(--border)] bg-[var(--surface)]'
                    }`}>
                    <div className="min-w-0">
                      <div className="truncate text-sm font-bold">
                        {d.name}{ext && <span className="ml-1 text-xs font-semibold text-[var(--muted)]">.{ext}</span>}
                      </div>
                      <div className={`truncate text-xs ${d.submitted ? 'text-[var(--success)]' : 'text-[var(--muted)]'}`}>
                        {d.submitted ? `✓ ${d.fileName ?? '제출 완료'}` : '아직 제출하지 않았어요'}
                      </div>
                    </div>
                    {!d.submitted && (
                      <button type="button" className="btn btn-primary btn-small flex-shrink-0" onClick={() => setSubmitting(d)}>
                        제출하기
                      </button>
                    )}
                  </div>
                );
              })}
            </>
          )}
        </div>

        <footer className="flex items-center justify-between gap-2 border-t border-[var(--border-soft)] px-5 py-4">
          <Link to={`/workspaces/${workspaceId}/tasks/${taskId}`} className="text-xs font-semibold">상세 화면에서 보기 →</Link>
          <button type="button" className={`btn ${done ? 'btn-primary' : ''}`} onClick={onClose}>
            {done ? '완료' : '닫기'}
          </button>
        </footer>
      </div>

      {submitting && (
        <DeliverableSubmitDialog workspaceId={workspaceId} taskId={String(taskId)} deliverable={submitting}
          onClose={() => setSubmitting(null)}
          onSubmitted={() => { setSubmitting(null); load(); }} />
      )}
    </div>
  );
}
