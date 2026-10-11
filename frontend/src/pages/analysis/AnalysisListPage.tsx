// UI-08 논의 정리 검토 대기 목록 (담당: B)
import { Link, useParams } from 'react-router';
import { getPendingAnalyses, STATUS_TEXT } from './api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import { formatDateTime } from '../../utils/date';

export default function AnalysisListPage() {
  const { workspaceId = '' } = useParams();
  const list = useAsync(() => getPendingAnalyses(workspaceId), [workspaceId]);

  return (
    <section className="stack">
      <div className="row">
        <h1>논의 정리 검토 대기</h1>
        <span className="spacer" />
        <Link to={`/workspaces/${workspaceId}/slack`}>← Slack</Link>
      </div>
      <p className="small muted">
        Slack 스레드에서 "논의 정리"를 실행하거나, 끝난 논의를 자동으로 분석한 결과입니다. 확정하기 전에는 외부 서비스에
        반영되지 않습니다.
      </p>

      {list.loading && !list.data && <Loading />}
      {list.error && <ErrorBox error={list.error} onRetry={list.reload} />}
      {list.data && list.data.length === 0 && <Empty>검토할 논의 정리가 없습니다.</Empty>}
      {list.data && list.data.length > 0 && (
        <ul className="list card">
          {list.data.map((a) => (
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
