// 다른 메뉴에 있는 동안 "논의 분석하기"가 진행 중이거나 끝났으면 오른쪽 아래에 알려 준다.
// (분석 화면에 있으면 그 화면이 직접 보여 주므로 띄우지 않는다)
import { useLocation, useNavigate } from 'react-router';
import { markSeen, retryAnalysis, useAnalysisRuns } from './analysisRunStore';

export default function AnalysisRunNotice({ workspaceId }: { workspaceId: string }) {
  const runs = useAnalysisRuns(workspaceId);
  const location = useLocation();
  const navigate = useNavigate();
  const onAnalysisRunPage = location.pathname.endsWith('/slack/analysis/new');
  const viewingThread = onAnalysisRunPage ? Number(new URLSearchParams(location.search).get('thread')) : null;

  const shown = runs.filter((run) =>
    run.threadId !== viewingThread && (run.state === 'running' || !run.seen));
  if (shown.length === 0) return null;

  return (
    <div className="fixed right-5 bottom-5 z-20 flex w-[300px] max-w-[calc(100vw-40px)] flex-col gap-2" role="status" aria-live="polite">
      {shown.map((run) => (
        <div key={run.threadId}
          className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-3.5 text-[13px] shadow-[var(--shadow)]">
          {run.state === 'running' && (
            <div className="flex items-center gap-2">
              <span className="h-2 w-2 shrink-0 animate-pulse rounded-full bg-[var(--primary)]" />
              <span className="flex-1">논의 분석 중이에요. 끝나면 알려 드릴게요.</span>
              <button type="button" className="btn btn-small"
                onClick={() => navigate(`/workspaces/${workspaceId}/slack/analysis/new?thread=${run.threadId}`)}>
                보기
              </button>
            </div>
          )}
          {run.state === 'done' && (
            <>
              <strong className="block">논의 분석이 끝났어요</strong>
              <span className="text-xs text-[var(--muted)]">결정사항·후속 작업을 검토하고 확정해 주세요.</span>
              <div className="mt-2.5 flex justify-end gap-1.5">
                <button type="button" className="btn btn-small" onClick={() => markSeen(workspaceId, run.threadId)}>닫기</button>
                <button type="button" className="btn btn-primary btn-small"
                  onClick={() => {
                    markSeen(workspaceId, run.threadId);
                    navigate(`/workspaces/${workspaceId}/slack/analysis/${run.analysisId}`);
                  }}>
                  결과 보기
                </button>
              </div>
            </>
          )}
          {run.state === 'failed' && (
            <>
              <strong className="block text-[var(--danger)]">논의 분석에 실패했어요</strong>
              <span className="text-xs text-[var(--muted)]">{run.message}</span>
              <div className="mt-2.5 flex justify-end gap-1.5">
                <button type="button" className="btn btn-small" onClick={() => markSeen(workspaceId, run.threadId)}>닫기</button>
                <button type="button" className="btn btn-primary btn-small"
                  onClick={() => {
                    retryAnalysis(workspaceId, run.threadId);
                    navigate(`/workspaces/${workspaceId}/slack/analysis/new?thread=${run.threadId}`);
                  }}>
                  다시 시도
                </button>
              </div>
            </>
          )}
        </div>
      ))}
    </div>
  );
}
