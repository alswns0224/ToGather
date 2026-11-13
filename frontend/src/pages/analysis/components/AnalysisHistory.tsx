import { useState } from 'react';
import { Link } from 'react-router';
import { getAnalyses, STATUS_TEXT, type AnalysisStatus } from '../api';
import { useAsync } from '../../../hooks/useAsync';
import { ErrorBox, Loading } from '../../../components/Status';
import { formatDateTime } from '../../../utils/date';

const STATUS_BADGE: Record<AnalysisStatus, string> = {
  CONFIRMED: 'badge-success',
  REVIEWING: 'badge-warning',
  ANALYZING: 'badge-primary',
  FAILED: 'badge-danger',
  NO_CONTENT: '',
};

/** 시안 왼쪽 칸 "분석 내역": 검색, 전체·검토 대기 탭, 스레드별 최신 분석 목록 */
export default function AnalysisHistory({
  workspaceId,
  selectedId,
  refreshKey = 0,
}: {
  workspaceId: string;
  selectedId: number | null;
  /** 바뀌면 목록을 다시 불러온다 (확정·취소 뒤 상태 반영) */
  refreshKey?: number;
}) {
  const list = useAsync(() => getAnalyses(workspaceId), [workspaceId, refreshKey]);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<'ALL' | 'PENDING'>('ALL');

  const q = query.trim().toLowerCase();
  const items = (list.data ?? []).filter(
    (a) =>
      (!q || `${a.threadTitle ?? ''} ${a.threadPreview ?? ''}`.toLowerCase().includes(q)) &&
      (filter === 'ALL' || a.status !== 'CONFIRMED'),
  );
  const pendingCount = (list.data ?? []).filter((a) => a.status !== 'CONFIRMED').length;

  return (
    <aside className="card analysis-history">
      <div className="flex items-center gap-1.5">
        <strong className="text-[15px]">분석 내역</strong>
        <span className="badge">{list.data?.length ?? 0}</span>
      </div>
      <input className="input" placeholder="🔍 논의 검색" value={query} onChange={(e) => setQuery(e.target.value)} />
      <div className="segmented">
        <button type="button" className={filter === 'ALL' ? 'active' : ''} onClick={() => setFilter('ALL')}>
          전체
        </button>
        <button type="button" className={filter === 'PENDING' ? 'active' : ''} onClick={() => setFilter('PENDING')}>
          검토 대기{pendingCount > 0 ? ` ${pendingCount}` : ''}
        </button>
      </div>

      {list.loading && !list.data && <Loading />}
      {list.error && <ErrorBox error={list.error} onRetry={list.reload} />}
      {list.data && items.length === 0 && (
        <div className="small muted">{filter === 'PENDING' ? '검토할 논의 정리가 없습니다.' : '논의 정리가 없습니다.'}</div>
      )}

      <div className="flex flex-col gap-1.5">
        {items.map((a) => (
          <Link
            key={a.analysisId}
            to={`/workspaces/${workspaceId}/slack/analysis/${a.analysisId}`}
            className={`analysis-history-item${a.analysisId === selectedId ? ' active' : ''}`}
          >
            <div className="text-[13px] font-bold">📄 {a.threadTitle ?? '제목 없는 스레드'}</div>
            <div className="mt-[3px] text-[11px] text-[var(--muted)]">
              {formatDateTime(a.createdAt)} · 항목 {a.itemCount}개{a.auto ? ' · 자동' : ''}
            </div>
            <span className={`badge ${STATUS_BADGE[a.status]} mt-[5px] !text-[10px]`}>{STATUS_TEXT[a.status]}</span>
          </Link>
        ))}
      </div>
    </aside>
  );
}
