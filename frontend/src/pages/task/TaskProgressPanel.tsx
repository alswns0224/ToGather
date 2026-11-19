// UI-09 팀 전체·담당자별 진행 현황 (담당: C)
import type { TaskProgressResponse } from './api';

export default function TaskProgressPanel({ data, loading, error, filtered, memberName, onSelectAssignee, onRetry }: {
  data: TaskProgressResponse | null;
  loading: boolean;
  error: string | null;
  filtered: boolean;
  memberName: (id: number | null) => string;
  onSelectAssignee: (id: number | null) => void;
  onRetry: () => void;
}) {
  return (
    <section className="card flex min-w-0 flex-col gap-4" aria-label="진행 현황 집계">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <strong className="text-[17px]">{filtered ? '조회 조건 진행 현황' : '팀 전체 진행 현황'}</strong>
          <p className="mb-0 mt-1 text-xs text-[var(--muted)]">선택한 조건의 전체 작업 기준 · 진행률은 각 Task 진행률의 평균</p>
        </div>
        <button type="button" className="btn btn-small" disabled={loading} onClick={onRetry}>새로고침</button>
      </div>
      {loading && <p className="m-0 text-sm text-[var(--muted)]">진행 현황을 불러오는 중...</p>}
      {error && <div role="alert" className="alert alert-error">
        <span>{error}</span><button type="button" className="btn btn-small" onClick={onRetry}>다시 시도</button>
      </div>}
      {data && !loading && !error && <>
        <div className="flex flex-wrap items-center gap-3">
          <strong className="text-2xl text-[var(--primary)]">평균 {data.overall.progress}%</strong>
          <div className="h-2 min-w-24 flex-1 overflow-hidden rounded-full bg-[var(--surface-2)]">
            <div className="h-full rounded-full bg-[image:var(--brand-grad)]" style={{ width: `${data.overall.progress}%` }} />
          </div>
          <span className="badge badge-success">정상 {data.overall.normalCount}</span>
          <span className="badge badge-warning">주의 {data.overall.warningCount}</span>
          <span className="badge badge-danger">위험 {data.overall.dangerCount}</span>
        </div>
        <div className="flex flex-col gap-2">
          <strong className="text-sm">담당자별 진행 현황</strong>
          {data.byAssignee.map(({ assigneeId, summary }) => (
            <div key={assigneeId ?? 'unassigned'} className="flex min-w-0 flex-wrap items-center gap-3 rounded-lg bg-[var(--surface-2)] p-3 text-sm">
              <button type="button" className="link-button min-w-0 break-words text-left font-bold"
                onClick={() => onSelectAssignee(assigneeId)}>{memberName(assigneeId)}</button>
              <span className="text-xs text-[var(--muted)]">{summary.totalCount}건 · 완료 {summary.doneCount}</span>
              <div className="h-2 min-w-20 flex-1 overflow-hidden rounded-full bg-[var(--surface)]">
                <div className="h-full rounded-full bg-[image:var(--brand-grad)]" style={{ width: `${summary.progress}%` }} />
              </div>
              <strong className="text-xs">{summary.progress}%</strong>
              <span className="text-xs text-[var(--muted)]">주의 {summary.warningCount} · 위험 {summary.dangerCount}</span>
            </div>
          ))}
        </div>
        <p className="m-0 text-xs text-[var(--muted)]">마감 위험도 기준: {data.asOfDate} (한국 시간)</p>
      </>}
    </section>
  );
}
