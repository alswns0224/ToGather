// UI-08 논의 정리 목록 (담당: B) — 시안처럼 목록과 상세를 한 화면에 두므로, 검토할 정리가 있으면 그 상세로 바로 연다.
import { Link, Navigate, useParams } from 'react-router';
import { getAnalyses } from './api';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';
import AnalysisHistory from './components/AnalysisHistory';
import { AnalysisHeader } from './AnalysisPage';
import './analysis.css';

export default function AnalysisListPage() {
  const { workspaceId = '' } = useParams();
  const list = useAsync(() => getAnalyses(workspaceId), [workspaceId]);

  const first = list.data?.find((a) => a.status !== 'CONFIRMED') ?? list.data?.[0];
  if (first) return <Navigate to={`/workspaces/${workspaceId}/slack/analysis/${first.analysisId}`} replace />;

  return (
    <section className="stack">
      <AnalysisHeader />
      {list.loading && !list.data && <Loading />}
      {list.error && <ErrorBox error={list.error} onRetry={list.reload} />}
      {list.data && (
        <div className="analysis-board">
          <AnalysisHistory workspaceId={workspaceId} selectedId={null} />
          <div className="card analysis-main analysis-empty">
            <strong>아직 논의 정리가 없습니다.</strong>
            <span className="small muted">
              Slack 스레드를 열고 "논의 분석하기"를 누르면 결정사항·후속 작업·회의 제안을 정리해요.
            </span>
            <Link to={`/workspaces/${workspaceId}/slack`} className="btn btn-small">
              Slack으로 이동
            </Link>
          </div>
        </div>
      )}
    </section>
  );
}
