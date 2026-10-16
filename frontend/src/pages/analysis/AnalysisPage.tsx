// UI-08 논의 정리 · 실행 연계 (담당: B)
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  addItem,
  analyzeThread,
  confirmAnalysis,
  discardAnalysis,
  getAnalysis,
  ITEM_TYPE_TEXT,
  STATUS_TEXT,
  type AnalysisResultDTO,
  type ItemType,
} from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import { formatDateTime } from '../../utils/date';
import ItemCard, { ItemEditor } from './components/ItemCard';
import OutputPanel from './components/OutputPanel';
import './analysis.css';

const ITEM_TYPES: ItemType[] = ['DECISION', 'FOLLOW_UP', 'OPEN_ISSUE', 'MEETING_PROPOSAL'];
const STEPS = ['스레드 메시지 모으는 중', 'AI가 논의를 분석하는 중', '결과 정리하는 중'];

export default function AnalysisPage() {
  const { workspaceId = '', analysisId = '' } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();

  if (analysisId === 'new') {
    const thread = Number(params.get('thread'));
    return (
      <NewAnalysis
        workspaceId={workspaceId}
        contentRefId={thread}
        onDone={(id) => navigate(`/workspaces/${workspaceId}/slack/analysis/${id}`, { replace: true })}
      />
    );
  }
  return <AnalysisDetail key={analysisId} workspaceId={workspaceId} analysisId={Number(analysisId)} />;
}

/** "논의 정리" 실행: 분석이 끝날 때까지 처리 단계를 보여준다. (최대 30초) */
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
    <section className="stack">
      <h1>논의 정리</h1>
      {error ? (
        <ErrorBox error={new Error(`분석하지 못했습니다. ${error.message}`)} onRetry={run} />
      ) : (
        <ol className="card analysis-steps">
          {STEPS.map((s, i) => (
            <li key={s} className={i < step ? 'done' : i === step ? 'active' : ''}>
              {s}
              {i === step && '...'}
            </li>
          ))}
        </ol>
      )}
      <Link to={`/workspaces/${workspaceId}/slack`}>← Slack으로 돌아가기</Link>
    </section>
  );
}

function AnalysisDetail({ workspaceId, analysisId }: { workspaceId: string; analysisId: number }) {
  const navigate = useNavigate();
  const analysis = useAsync(() => getAnalysis(workspaceId, analysisId), [workspaceId, analysisId]);
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const [adding, setAdding] = useState<ItemType | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const data = analysis.data;
  const editable = !!data && data.status !== 'CONFIRMED' && data.status !== 'ANALYZING';

  const confirm = async () => {
    if (!data) return;
    if (!window.confirm('검토를 마치고 확정할까요? 확정한 뒤에는 항목을 수정할 수 없습니다.')) return;
    setBusy(true);
    setActionError(null);
    try {
      analysis.setData(await confirmAnalysis(workspaceId, data.analysisId));
    } catch (e) {
      setActionError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const discard = async () => {
    if (!data) return;
    if (!window.confirm('정리 결과를 취소할까요? 외부 서비스에는 아무것도 반영되지 않습니다.')) return;
    setBusy(true);
    setActionError(null);
    try {
      await discardAnalysis(workspaceId, data.analysisId);
      navigate(`/workspaces/${workspaceId}/slack/analyses`, { replace: true });
    } catch (e) {
      setActionError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <section className="stack">
      <div className="row">
        <h1>논의 정리</h1>
        {data && <span className={`badge status-${data.status.toLowerCase()}`}>{STATUS_TEXT[data.status]}</span>}
        {data?.auto && <span className="badge">자동 수집</span>}
        <span className="spacer" />
        <Link to={`/workspaces/${workspaceId}/slack/analyses`} className="small">
          검토 대기 목록
        </Link>
      </div>

      {analysis.loading && !data && <Loading />}
      {analysis.error && <ErrorBox error={analysis.error} onRetry={analysis.reload} />}

      {data && (
        <>
          <ThreadSummary workspaceId={workspaceId} data={data} />

          {data.status === 'FAILED' && (
            <div className="alert alert-error">
              AI 분석에 실패했습니다. Slack 스레드에서 다시 "논의 정리"를 실행하거나, 아래에서 직접 작성할 수 있습니다.
            </div>
          )}
          {editable && data.items.length === 0 && (
            <Empty>추출된 결정사항이 없습니다. 아래 양식에서 직접 작성할 수 있습니다.</Empty>
          )}

          {ITEM_TYPES.map((type) => {
            const items = data.items.filter((i) => i.itemType === type);
            if (!editable && items.length === 0) return null;
            return (
              <div key={type} className="card stack">
                <div className="row">
                  <h2>{ITEM_TYPE_TEXT[type]}</h2>
                  <span className="small muted">{items.length}개</span>
                  <span className="spacer" />
                  {editable && adding !== type && (
                    <button className="btn btn-small" onClick={() => setAdding(type)}>
                      + 추가
                    </button>
                  )}
                </div>
                {items.length > 0 && (
                  <ul className="list">
                    {items.map((item) => (
                      <ItemCard
                        key={item.itemId}
                        workspaceId={workspaceId}
                        item={item}
                        editable={editable}
                        members={members.data ?? []}
                        messages={data.messages}
                        onChanged={analysis.reload}
                      />
                    ))}
                  </ul>
                )}
                {adding === type && (
                  <ItemEditor
                    itemType={type}
                    members={members.data ?? []}
                    onCancel={() => setAdding(null)}
                    onSubmit={async (form) => {
                      await addItem(workspaceId, data.analysisId, { ...form, itemType: type });
                      setAdding(null);
                      analysis.reload();
                    }}
                  />
                )}
              </div>
            );
          })}

          {actionError && <div className="alert alert-error">{actionError}</div>}

          {editable && (
            <div className="row">
              <button className="btn" onClick={discard} disabled={busy}>
                취소 (반영 안 함)
              </button>
              <span className="spacer" />
              <button className="btn btn-primary" onClick={confirm} disabled={busy || data.items.length === 0}>
                검토 완료 · 확정
              </button>
            </div>
          )}

          {data.status === 'CONFIRMED' && (
            <OutputPanel workspaceId={workspaceId} analysis={data} onChanged={analysis.reload} />
          )}
        </>
      )}
    </section>
  );
}

function ThreadSummary({ workspaceId, data }: { workspaceId: string; data: AnalysisResultDTO }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="card stack">
      <div className="row small">
        <strong>{data.threadTitle}</strong>
        <span className="muted grow clamp">{data.threadPreview}</span>
        <Link to={`/workspaces/${workspaceId}/slack?channel=${data.channelId}`}>스레드 보기</Link>
        {data.threadLink && (
          <a href={data.threadLink} target="_blank" rel="noreferrer">
            Slack 원본
          </a>
        )}
      </div>
      <div className="row small muted">
        <span>분석 {formatDateTime(data.createdAt)}</span>
        {data.analyzedUntil && <span>· {formatDateTime(data.analyzedUntil)} 메시지까지 분석</span>}
        <button className="link-button" onClick={() => setOpen((o) => !o)}>
          {open ? '대화 접기' : `대화 보기 (${data.messages.length})`}
        </button>
      </div>
      {open && (
        <ul className="list small">
          {data.messages.map((m) => (
            <li key={m.contentRefId} className={m.analyzed ? '' : 'analysis-new-message'}>
              <strong>{m.authorName ?? '?'}</strong> <span className="muted">{m.createdAt && formatDateTime(m.createdAt)}</span>
              <div className="pre">{m.text}</div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
