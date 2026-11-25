// UI-10 회의 안건별 관련 자료·산출물 (담당: A)
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import NavIcon from '../../layouts/NavIcon';
import { ErrorBox, Loading } from '../../components/Status';
import { getTaskContext, type TaskContext } from '../task/api';
import { ContextContentCard } from '../task/TaskContextPanels';
import type { AgendaResponse } from './api';

const RISK_TEXT = { NORMAL: '정상', WARNING: '주의', DANGER: '위험' } as const;

export default function MeetingAgendaCard({ workspaceId, agenda, editable, onRemove }: {
  workspaceId: string;
  agenda: AgendaResponse;
  editable: boolean;
  onRemove: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [context, setContext] = useState<TaskContext | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error>();
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    if (!open || !agenda.task) return;
    let active = true;
    setLoading(true);
    setError(undefined);
    getTaskContext(workspaceId, String(agenda.taskId))
      .then((result) => { if (active) setContext(result); })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason : new Error('안건 자료를 불러오지 못했습니다.'));
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [workspaceId, agenda.taskId, agenda.task !== null, open, revision]);

  const task = agenda.task ? context?.task ?? agenda.task : null;
  const done = task?.status === 'DONE';
  const references = context
    ? [...new Map([...Object.values(context.contentsByProvider).flat(), ...context.originEvidence]
      .map((reference) => [reference.contentRefId, reference])).values()]
    : [];
  const refresh = () => setRevision((value) => value + 1);

  return (
    <article className="overflow-hidden rounded-xl border border-[var(--border-soft)]">
      <div className="meeting-agenda !flex-wrap !rounded-none !border-0">
        <div className="flex min-w-0 basis-[220px] flex-1 items-center gap-2">
          <span className="text-[var(--muted)]"><NavIcon name={agenda.title ? 'meetings' : 'tasks'} /></span>
          {task ? (
            <Link to={`/workspaces/${workspaceId}/tasks/${agenda.taskId}`}
              className="min-w-0 break-words text-sm font-semibold text-[var(--text)] no-underline">
              {task.title}
            </Link>
          ) : agenda.title ? <>
            <strong className="min-w-0 break-words text-sm font-semibold text-[var(--text)]">{agenda.title}</strong>
            <span className="shrink-0 rounded-md bg-[var(--primary-soft)] px-2 py-0.5 text-[10px] font-semibold text-[var(--primary)]">직접 입력</span>
          </> : <span className="text-sm text-[var(--muted)]">(삭제된 팀 작업)</span>}
          {task && task.riskLevel !== 'NORMAL' && !done && (
            <span className={`badge ${task.riskLevel === 'DANGER' ? 'badge-danger' : 'badge-warning'} !text-[10px]`}>
              {RISK_TEXT[task.riskLevel]}
            </span>
          )}
        </div>
        <div className="ml-auto flex flex-shrink-0 items-center gap-2.5">
          {task && (
            <>
              <span className={`text-xs font-bold ${done ? 'text-[var(--success)]' : 'text-[var(--muted)]'}`}>
                {done ? '완료' : '진행 중'} · {task.progress}%
              </span>
              <div className="h-2 w-[72px] overflow-hidden rounded-full bg-[var(--surface)]">
                <div className="h-full rounded-full" style={{ width: `${task.progress}%`,
                  background: done ? 'var(--success)' : 'var(--brand-grad)' }} />
              </div>
            </>
          )}
          {editable && <button type="button" className="analysis-ghost" onClick={onRemove}>빼기</button>}
        </div>
      </div>
      {agenda.task && (
        <details open={open} onToggle={(event) => setOpen(event.currentTarget.open)} className="px-3.5 pb-3">
          <summary className="cursor-pointer py-2 text-xs font-semibold text-[var(--primary)]">자료·산출물 보기</summary>
          {open && (
            <div className="flex min-w-0 flex-col gap-3 pt-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <Link to={`/workspaces/${workspaceId}/tasks/${agenda.taskId}`} className="text-xs">Task에서 제출·관리 →</Link>
                <button type="button" className="btn btn-small" disabled={loading} onClick={refresh}>
                  {loading ? '조회 중...' : '새로고침'}
                </button>
              </div>
              {loading && <Loading text="안건 자료와 산출물을 불러오는 중..." />}
              {error && <ErrorBox error={error} onRetry={refresh} />}
              {context && !error && !loading && (
                <>
                  <h4 className="m-0 text-sm">연결·근거 자료 <span className="badge">{references.length}</span></h4>
                  {references.length === 0 && <p className="m-0 text-xs text-[var(--muted)]">연결된 자료가 없습니다.</p>}
                  {references.map((reference) => (
                    <ContextContentCard key={reference.contentRefId} workspaceId={workspaceId} reference={reference}
                      state={context.contentStates[reference.contentRefId]} />
                  ))}
                  <h4 className="m-0 text-sm">필수 산출물 <span className="badge">
                    제출 {context.deliverables.filter((item) => item.submitted).length}/{context.deliverables.length}
                  </span></h4>
                  {context.deliverables.length === 0 && (
                    <p className="m-0 text-xs text-[var(--muted)]">등록된 산출물이 없습니다.</p>
                  )}
                  <ul className="m-0 flex list-none flex-col gap-2 p-0">
                    {context.deliverables.map((item) => (
                      <li key={item.deliverableId} className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-[var(--surface-2)] p-3 text-sm">
                        <div className="min-w-0 break-words">
                          <strong>{item.name}</strong>
                          {item.ext && <span className="ml-1 text-xs text-[var(--muted)]">.{item.ext.replace(/^\./, '')}</span>}
                          {item.submitted && item.fileName && <p className="m-0 mt-1 text-xs text-[var(--muted)]">{item.fileName}</p>}
                        </div>
                        <div className="flex items-center gap-2">
                          <span className={`badge ${item.submitted ? 'badge-success' : ''}`}>{item.submitted ? '제출 완료' : '미제출'}</span>
                          {item.submitted && item.link && /^https?:\/\//i.test(item.link) && (
                            <a href={item.link} target="_blank" rel="noreferrer" className="text-xs">원본 파일 ↗</a>
                          )}
                        </div>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          )}
        </details>
      )}
    </article>
  );
}
