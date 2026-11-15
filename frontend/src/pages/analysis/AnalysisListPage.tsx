// UI-08 논의 정리 목록 (담당: B)
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { getAnalyses, STATUS_TEXT } from './api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import { formatDateTime } from '../../utils/date';
import { CARD, MUTED, SUB_CARD } from '../../components/ui';

export default function AnalysisListPage() {
  const { workspaceId = '' } = useParams();
  // 스레드별 최신 분석 (확정 포함)
  const list = useAsync(() => getAnalyses(workspaceId), [workspaceId]);
  const [tab, setTab] = useState<'pending' | 'confirmed'>('pending');
  const items = (list.data ?? []).filter((a) => (tab === 'confirmed' ? a.status === 'CONFIRMED' : a.status !== 'CONFIRMED'));
  const pendingCount = (list.data ?? []).filter((a) => a.status !== 'CONFIRMED').length;

  return (
    <section className="stack">
      <PageHeader
        eyebrow="AI CONTEXT SYNTHESIS"
        title="논의 정리"
        description='Slack 스레드에서 "논의 정리"를 실행하거나, 끝난 논의를 자동으로 분석한 결과입니다. 확정하기 전에는 외부 서비스에 반영되지 않습니다.'
        actions={
          <Link to={`/workspaces/${workspaceId}/slack`} className="btn btn-small">
            ← Slack
          </Link>
        }
      />

      <div className={`${SUB_CARD} inline-flex gap-1 self-start p-1`}>
        {(['pending', 'confirmed'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`cursor-pointer rounded-xl border-0 px-4 py-1.5 text-xs font-bold transition ${
              tab === t ? 'bg-indigo-600 text-white' : `bg-transparent ${MUTED}`
            }`}
          >
            {t === 'pending' ? `검토 대기${pendingCount > 0 ? ` (${pendingCount})` : ''}` : '확정됨'}
          </button>
        ))}
      </div>

      {list.loading && !list.data && <Loading />}
      {list.error && <ErrorBox error={list.error} onRetry={list.reload} />}
      {list.data && items.length === 0 && (
        <Empty>{tab === 'pending' ? '검토할 논의 정리가 없습니다.' : '확정한 논의 정리가 없습니다.'}</Empty>
      )}
      {items.length > 0 && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {items.map((a) => (
            <Link
              key={a.analysisId}
              to={`/workspaces/${workspaceId}/slack/analysis/${a.analysisId}`}
              className={`${CARD} group block p-6 no-underline transition-all hover:-translate-y-0.5 hover:border-indigo-500/60`}
            >
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <span className={`badge status-${a.status.toLowerCase()}`}>{STATUS_TEXT[a.status]}</span>
                {a.auto && <span className="badge">자동</span>}
                <span className="flex-1" />
                <span className="text-[11px] font-bold text-indigo-500">✨ 항목 {a.itemCount}개</span>
              </div>
              <h3 className="m-0 text-base font-bold transition-colors group-hover:text-indigo-500">{a.threadTitle}</h3>
              <p className={`m-0 mt-1 line-clamp-2 text-xs leading-relaxed ${MUTED}`}>{a.threadPreview}</p>
              <p className={`m-0 mt-3 text-[11px] ${MUTED}`}>{formatDateTime(a.createdAt)}</p>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}
