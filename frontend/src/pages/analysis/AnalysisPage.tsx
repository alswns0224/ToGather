// UI-08 논의 정리 실행 (담당: B): Slack 스레드의 "논의 정리"에서 들어온다.
import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router';
import { analyzeThread } from './api';
import { ErrorBox } from '../../components/Status';
import './analysis.css';

const STEPS = ['스레드 메시지 모으는 중', 'AI가 논의를 분석하는 중', '결과 정리하는 중'];

export default function AnalysisPage() {
  const { workspaceId = '', analysisId = '' } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();

  if (analysisId !== 'new') {
    // 예전 주소(/slack/analysis/:id)는 논의 정리 화면으로 보낸다.
    return <Navigate to={`/workspaces/${workspaceId}/slack/analyses?id=${analysisId}`} replace />;
  }
  return (
    <NewAnalysis
      workspaceId={workspaceId}
      contentRefId={Number(params.get('thread'))}
      onDone={(id) => navigate(`/workspaces/${workspaceId}/slack/analyses?id=${id}`, { replace: true })}
    />
  );
}

/** 분석이 끝날 때까지 처리 단계를 보여준다. (최대 30초) */
function NewAnalysis({
  workspaceId,
  contentRefId,
  onDone,
}: {
  workspaceId: string;
  contentRefId: number;
  onDone: (analysisId: number) => void;
}) {
  const [step, setStep] = useState(0);
  const [error, setError] = useState<Error | null>(null);
  const started = useRef(false);

  const run = () => {
    setError(null);
    setStep(0);
    const timers = [setTimeout(() => setStep(1), 800), setTimeout(() => setStep(2), 12000)];
    analyzeThread(workspaceId, contentRefId)
      .then((result) => onDone(result.analysisId))
      .catch((e: Error) => setError(e))
      .finally(() => timers.forEach(clearTimeout));
  };

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div>
      <section className="page-intro">
        <div>
          <div className="collection-title">논의 정리</div>
          <div className="collection-description">Slack 스레드의 새 메시지를 분석하고 있어요.</div>
        </div>
      </section>
      {error ? (
        <ErrorBox error={new Error(`분석하지 못했어요. ${error.message}`)} onRetry={run} />
      ) : (
        <ol className="surface analysis-steps">
          {STEPS.map((s, i) => (
            <li key={s} className={i < step ? 'done' : i === step ? 'active' : ''}>
              {s}
              {i === step && '...'}
            </li>
          ))}
        </ol>
      )}
      <p style={{ marginTop: 16 }}>
        <Link to={`/workspaces/${workspaceId}/slack`}>← 대화로 돌아가기</Link>
      </p>
    </div>
  );
}
