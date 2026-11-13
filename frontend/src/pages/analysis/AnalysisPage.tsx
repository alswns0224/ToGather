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
  type AnalysisStatus,
  type ItemType,
} from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import ServiceLogo from '../../components/ServiceLogo';
import { formatDateTime } from '../../utils/date';
import ItemCard, { ItemEditor } from './components/ItemCard';
import OutputPanel from './components/OutputPanel';
import './analysis.css';
import PageHeader from '../../components/PageHeader';
import { BTN_AI, BTN_SUB } from '../../components/ui';
import AnalysisHistory from './components/AnalysisHistory';

const TYPE_ICON: Record<ItemType, string> = { DECISION: '⚑', FOLLOW_UP: '☑', OPEN_ISSUE: '❓', MEETING_PROPOSAL: '📅' };

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
      <AnalysisHeader status="ANALYZING" />
      {error ? (
        <ErrorBox error={new Error(`분석하지 못했습니다. ${error.message}`)} onRetry={run} />
      ) : (
        <ol className="card analysis-steps analysis-analyzing">
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


/** 진행 단계 표시: 대화 수집 → AI 분석 → 검토 → 확정 (시안 머리 오른쪽의 알약) */
function StepPill({ status }: { status?: AnalysisStatus }) {
  const analyzed = !!status && status !== 'ANALYZING';
  const confirmed = status === 'CONFIRMED';
  const reviewing = status === 'REVIEWING' || status === 'FAILED' || status === 'NO_CONTENT';
  return (
    <div className="card analysis-step-pill">
      <span className="text-[var(--success)]">✓ 대화 수집</span>
      <span className="text-[var(--muted)]">→</span>
      <span className={analyzed ? 'text-[var(--success)]' : 'text-[var(--primary)]'}>
        {analyzed ? '✓ AI 분석' : '● AI 분석'}
      </span>
      <span className="text-[var(--muted)]">→</span>
      <span className={`badge ${confirmed ? 'badge-success' : reviewing ? 'badge-primary' : ''}`}>
        {confirmed ? '✓ 검토 완료' : reviewing ? '● 검토 중' : '○ 검토'}
      </span>
      <span className="text-[var(--muted)]">→</span>
      <span className={`badge ${confirmed ? 'badge-success' : ''}`}>{confirmed ? '✓ 확정됨' : '○ 확정'}</span>
    </div>
  );
}

export function AnalysisHeader({ status }: { status?: AnalysisStatus }) {
  return (
    <PageHeader
      icon={<span className="text-[22px] leading-6 text-[var(--primary)]">✦</span>}
      title="논의 정리"
      description={
        <>
          AI가 정리한 결정과 후속 작업을 검토하고 확정하세요.
          <span className="mt-0.5 block text-xs">ⓘ 확정 전에는 외부 서비스에 반영되지 않아요.</span>
        </>
      }
      actions={<StepPill status={status} />}
    />
  );
}

function AnalysisDetail({ workspaceId, analysisId }: { workspaceId: string; analysisId: number }) {
  const navigate = useNavigate();
  const analysis = useAsync(() => getAnalysis(workspaceId, analysisId), [workspaceId, analysisId]);
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const [adding, setAdding] = useState<ItemType | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [historyTick, setHistoryTick] = useState(0);

  const data = analysis.data;
  const editable = !!data && data.status !== 'CONFIRMED' && data.status !== 'ANALYZING';

  const confirm = async () => {
    if (!data) return;
    if (!window.confirm('검토를 마치고 확정할까요? 확정한 뒤에는 항목을 수정할 수 없습니다.')) return;
    setBusy(true);
    setActionError(null);
    try {
      analysis.setData(await confirmAnalysis(workspaceId, data.analysisId));
      setHistoryTick((t) => t + 1);
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
      <AnalysisHeader status={data?.status} />

      {data?.status === 'FAILED' && (
        <div className="alert alert-error">
          AI 분석에 실패했습니다. Slack 스레드에서 다시 "논의 분석하기"를 실행하거나, 아래에서 직접 작성할 수 있습니다.
        </div>
      )}

      <div className="analysis-board">
        <AnalysisHistory workspaceId={workspaceId} selectedId={analysisId} refreshKey={historyTick} />

        <div className="card analysis-main">
          {analysis.loading && !data && <Loading />}
          {analysis.error && <ErrorBox error={analysis.error} onRetry={analysis.reload} />}
          {data && (
            <>
              <div className="analysis-main-head">
                <h2 className="!m-0 !text-lg !font-extrabold">📄 {data.threadTitle ?? '제목 없는 스레드'}</h2>
                <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs text-[var(--muted)]">
                  <span className="inline-flex h-5 w-5">
                    <ServiceLogo service="slack" />
                  </span>
                  <span>분석 {formatDateTime(data.createdAt)}</span>
                  {data.analyzedUntil && <span>· {formatDateTime(data.analyzedUntil)} 메시지까지</span>}
                  <span className={`badge status-${data.status.toLowerCase()}`}>{STATUS_TEXT[data.status]}</span>
                  {data.auto && <span className="badge">자동 수집</span>}
                </div>
              </div>

              {editable && data.items.length === 0 && (
                <Empty>추출된 결정사항이 없습니다. 아래에서 직접 작성할 수 있습니다.</Empty>
              )}

              {ITEM_TYPES.map((type) => {
                const items = data.items.filter((i) => i.itemType === type);
                if (!editable && items.length === 0) return null;
                return (
                  <div key={type} className={`analysis-section type-${type.toLowerCase()}`}>
                    <div className="flex items-center justify-between">
                      <strong className="text-sm">
                        {TYPE_ICON[type]} {ITEM_TYPE_TEXT[type]} <span className="badge">{items.length}</span>
                      </strong>
                      {editable && adding !== type && (
                        <button type="button" className="analysis-ghost" onClick={() => setAdding(type)}>
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
                <div className="analysis-main-foot">
                  <div className="grow">
                    <div className="text-[13px] font-bold">내용과 근거를 확인한 뒤 확정해 주세요.</div>
                    <div className="text-[11px] text-[var(--muted)]">확정 후 문서·팀 작업·일정을 만들 수 있어요.</div>
                  </div>
                  <button type="button" className={BTN_SUB} onClick={discard} disabled={busy}>
                    취소 (반영 안 함)
                  </button>
                  <button
                    type="button"
                    className={`${BTN_AI} !h-10 !px-[18px]`}
                    onClick={confirm}
                    disabled={busy || data.items.length === 0}
                  >
                    검토 완료 · 확정
                  </button>
                </div>
              )}
              {data.status === 'CONFIRMED' && (
                <div className="analysis-main-foot">
                  <div className="grow text-[13px] font-bold text-[var(--success)]">✓ 확정 완료됨</div>
                  <span className="text-[11px] text-[var(--muted)]">오른쪽에서 문서·팀 작업·일정을 만들 수 있어요.</span>
                </div>
              )}
            </>
          )}
        </div>

        <div className="analysis-side">
          {data && <EvidencePanel workspaceId={workspaceId} data={data} />}
          {data?.status === 'CONFIRMED' ? (
            <div className="card analysis-side-card">
              <OutputPanel workspaceId={workspaceId} analysis={data} onChanged={analysis.reload} />
            </div>
          ) : (
            data && <NextActions data={data} />
          )}
        </div>
      </div>
    </section>
  );
}

/** 오른쪽 위: 분석한 스레드의 대화 (분석 뒤에 달린 메시지는 강조) */
function EvidencePanel({ workspaceId, data }: { workspaceId: string; data: AnalysisResultDTO }) {
  return (
    <div className="card analysis-side-card">
      <div>
        <div className="flex items-center gap-1.5">
          <strong className="text-sm">근거 대화</strong>
          <span className="badge">{data.messages.length}</span>
        </div>
        <div className="mt-0.5 text-xs text-[var(--muted)]">AI가 읽은 스레드의 발언이에요.</div>
      </div>
      <div className="analysis-quotes">
        {data.messages.map((m) => (
          <div key={m.contentRefId} className={`analysis-quote${m.analyzed ? '' : ' new'}`}>
            <div className="flex items-center gap-1.5 text-xs font-bold">
              <span className="inline-flex h-[18px] w-[18px] flex-shrink-0">
                <ServiceLogo service="slack" />
              </span>
              <span>
                {m.authorName ?? '?'}
                {m.createdAt && ` · ${formatDateTime(m.createdAt)}`}
              </span>
              {!m.analyzed && <span className="badge badge-primary !text-[10px]">분석 후 추가</span>}
            </div>
            <div className="pre mt-[5px] text-xs">{m.text}</div>
          </div>
        ))}
      </div>
      <div className="flex flex-wrap gap-3 text-xs font-semibold">
        <Link to={`/workspaces/${workspaceId}/slack?channel=${data.channelId}`}>스레드 보기</Link>
        {data.threadLink && (
          <a href={data.threadLink} target="_blank" rel="noreferrer">
            ↗ Slack 원본 보기
          </a>
        )}
      </div>
    </div>
  );
}

/** 확정 전: 확정하면 할 수 있는 작업 안내 */
function NextActions({ data }: { data: AnalysisResultDTO }) {
  const count = (t: ItemType) => data.items.filter((i) => i.itemType === t).length;
  return (
    <div className="card analysis-side-card">
      <strong className="text-sm">확정 후 가능한 작업</strong>
      <div className="analysis-next">
        <span>📄 Notion 결정사항 문서 작성</span>
        <span>›</span>
      </div>
      <div className="analysis-next">
        <span>☑ 후속 작업 {count('FOLLOW_UP')}개를 팀 작업으로 생성</span>
        <span>›</span>
      </div>
      <div className="analysis-next">
        <span>📅 회의 제안 {count('MEETING_PROPOSAL')}개를 일정으로 등록</span>
        <span>›</span>
      </div>
      <div className="text-[11px] text-[var(--muted)]">가운데에서 "검토 완료 · 확정"을 누르면 사용할 수 있어요.</div>
    </div>
  );
}
