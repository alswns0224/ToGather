// UI-08 논의 정리 목록 (담당: B)
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { getAnalyses, STATUS_TEXT } from './api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import { formatDateTime } from '../../utils/date';

export default function AnalysisListPage() {
  const { workspaceId = '' } = useParams();
  // 스레드별 최신 분석 (확정 포함)
  const list = useAsync(() => getAnalyses(workspaceId), [workspaceId]);
  const [tab, setTab] = useState<'pending' | 'confirmed'>('pending');
  const items = (list.data ?? []).filter((a) => (tab === 'confirmed' ? a.status === 'CONFIRMED' : a.status !== 'CONFIRMED'));
  const pendingCount = (list.data ?? []).filter((a) => a.status !== 'CONFIRMED').length;

  return (
    <section className="stack">
      <div className="row">
        <h1>논의 정리</h1>
        <span className="spacer" />
        <Link to={`/workspaces/${workspaceId}/slack`}>← Slack</Link>
      </div>
      <p className="small muted">
        Slack 스레드에서 "논의 정리"를 실행하거나, 끝난 논의를 자동으로 분석한 결과입니다. 확정하기 전에는 외부 서비스에
        반영되지 않습니다.
      </p>

      <div className="segmented">
        <button className={tab === 'pending' ? 'active' : ''} onClick={() => setTab('pending')}>
          검토 대기 {pendingCount > 0 && `(${pendingCount})`}
        </button>
        <button className={tab === 'confirmed' ? 'active' : ''} onClick={() => setTab('confirmed')}>
          확정됨
        </button>
      </div>

      {list.loading && !list.data && <Loading />}
      {list.error && <ErrorBox error={list.error} onRetry={list.reload} />}
      {list.data && items.length === 0 && (
        <Empty>{tab === 'pending' ? '검토할 논의 정리가 없습니다.' : '확정한 논의 정리가 없습니다.'}</Empty>
      )}
      {items.length > 0 && (
        <ul className="list card">
          {items.map((a) => (
            <li key={a.analysisId}>
              <Link to={`/workspaces/${workspaceId}/slack/analysis/${a.analysisId}`} className="row link-row">
                <span className={`badge status-${a.status.toLowerCase()}`}>{STATUS_TEXT[a.status]}</span>
                {a.auto && <span className="badge">자동</span>}
                <strong>{a.threadTitle}</strong>
                <span className="grow clamp small">{a.threadPreview}</span>
                <span className="small muted">
                  항목 {a.itemCount} · {formatDateTime(a.createdAt)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
