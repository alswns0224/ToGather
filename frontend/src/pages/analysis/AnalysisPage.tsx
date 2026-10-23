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
import PageHeader from '../../components/PageHeader';
import { BTN_AI, BTN_SUB, CARD, MUTED } from '../../components/ui';

const TYPE_ICON: Record<ItemType, string> = { DECISION: '🎯', FOLLOW_UP: '✅', OPEN_ISSUE: '❓', MEETING_PROPOSAL: '📅' };

/** AI 결과 강조 카드 (시안의 ai-glow-border) */
const AI_GLOW =
  'rounded-[28px] border border-violet-500/35 bg-gradient-to-br from-indigo-500/10 via-purple-500/10 to-pink-500/5 p-6 shadow-[0_8px_30px_-6px_rgba(99,102,241,0.12)]';

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
      <PageHeader eyebrow="AI CONTEXT SYNTHESIS" title="논의 정리" description="Gemini가 Slack 스레드를 읽고 결정사항과 후속 작업을 정리하고 있습니다." />
      {error ? (
        <ErrorBox error={new Error(`분석하지 못했습니다. ${error.message}`)} onRetry={run} />
      ) : (
        <ol className={`${AI_GLOW} analysis-steps`}>
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
      <PageHeader
        eyebrow="AI CONTEXT SYNTHESIS"
        title={
          <span className="inline-flex flex-wrap items-center gap-2">
            논의 정리
            {data && <span className={`badge status-${data.status.toLowerCase()}`}>{STATUS_TEXT[data.status]}</span>}
            {data?.auto && <span className="badge">자동 수집</span>}
          </span>
        }
        description="AI가 추출한 초안은 검토 및 확정 전까지 외부 서비스(Notion · Task · Calendar)에 반영되지 않습니다."
        actions={
          <Link to={`/workspaces/${workspaceId}/slack/analyses`} className="btn btn-small">
            검토 대기 목록
          </Link>
        }
      />

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
              <div key={type} className={`${CARD} stack p-6`}>
                <div className="row">
                  <h2 className="!text-base">{TYPE_ICON[type]} {ITEM_TYPE_TEXT[type]}</h2>
                  <span className="rounded-full bg-indigo-500/15 px-2.5 py-0.5 text-[11px] font-bold text-indigo-500">{items.length}개</span>
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
            <div className={`${CARD} sticky bottom-4 z-10 flex flex-wrap items-center gap-3 px-6 py-4`}>
              <p className={`m-0 flex-1 text-xs ${MUTED}`}>
                확정하면 결정 문서(Notion), 후속 작업(Task), 회의 제안(Calendar)을 만들 수 있습니다.
              </p>
              <button className={BTN_SUB} onClick={discard} disabled={busy}>
                취소 (반영 안 함)
              </button>
              <button className={BTN_AI} onClick={confirm} disabled={busy || data.items.length === 0}>
                ✨ 검토 완료 · 확정
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
    <div className={`${AI_GLOW} stack`}>
      <span className="text-[11px] font-bold tracking-wider text-violet-500 uppercase">✨ 원본 Slack 스레드</span>
      <div className="row small">
        <strong className="text-base">{data.threadTitle}</strong>
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
