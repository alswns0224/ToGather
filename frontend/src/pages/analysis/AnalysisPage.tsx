// UI-08 논의 정리 · 실행 연계 (담당: B)
import { useEffect, useState } from 'react';
import { getRun, markSeen, resumeOrStart, retryAnalysis, useAnalysisRun } from './analysisRunStore';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  addItem,
  confirmAnalysis,
  discardAnalysis,
  getAnalysis,
  ITEM_TYPE_TEXT,
  STATUS_TEXT,
  type AnalysisStatus,
  type ItemType,
} from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import ServiceLogo from '../../components/ServiceLogo';
import NavIcon from '../../layouts/NavIcon';
import { formatDateTime } from '../../utils/date';
import ItemCard, { ItemEditor } from './components/ItemCard';
import OutputPanel from './components/OutputPanel';
import './analysis.css';
import PageHeader from '../../components/PageHeader';
import { BTN_AI, BTN_SUB } from '../../components/ui';
import AnalysisHistory from './components/AnalysisHistory';
import AnalysisIcon, { type AnalysisIconName } from './components/AnalysisIcon';
import EvidenceDrawer from './components/EvidenceDrawer';

const TYPE_ICON: Record<ItemType, AnalysisIconName> = {
  DECISION: 'check',
  FOLLOW_UP: 'task',
  OPEN_ISSUE: 'question',
  MEETING_PROPOSAL: 'calendar',
};

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

/**
 * "논의 정리" 실행: 분석이 끝날 때까지 처리 단계를 보여준다. (보통 15초 안팎, 최대 90초)
 * 진행 상태는 analysisRunStore에 있어, 다른 메뉴에 갔다 와도 이어서 보이고 요청을 다시 보내지 않는다.
 */
function NewAnalysis({
  workspaceId,
  contentRefId,
  onDone,
}: {
  workspaceId: string;
  contentRefId: number;
  onDone: (analysisId: number) => void;
}) {
  const run = useAnalysisRun(workspaceId, contentRefId);
  const [now, setNow] = useState(() => Date.now());

  // 진행 중이거나 아직 보지 않은 결과가 있으면 그것을 보여 준다. 이미 본 결과뿐이면(같은 스레드를 다시 분석) 새로 시작한다.
  // 기억이 없으면(처음이거나 새로고침) 서버 상태를 보고 이어 가거나 새로 시작한다.
  useEffect(() => {
    const existing = getRun(workspaceId, contentRefId);
    if (existing && existing.state !== 'running' && existing.seen) {
      retryAnalysis(workspaceId, contentRefId);
    } else {
      resumeOrStart(workspaceId, contentRefId);
    }
  }, [workspaceId, contentRefId]);

  useEffect(() => {
    if (run?.state !== 'done') return;
    markSeen(workspaceId, contentRefId);
    onDone(run.analysisId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run]);

  useEffect(() => {
    if (run?.state !== 'running') return;
    const timer = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, [run?.state]);

  // 처리 단계는 시작한 뒤 지난 시간으로 보여 준다. (다른 메뉴에 갔다 와도 같은 단계)
  const elapsed = run ? now - run.startedAt : 0;
  const step = elapsed < 800 ? 0 : elapsed < 12000 ? 1 : 2;

  return (
    <section className="stack">
      <AnalysisHeader status="ANALYZING" />
      {run?.state === 'failed' ? (
        <ErrorBox error={new Error(`분석하지 못했습니다. ${run.message}`)}
          onRetry={() => retryAnalysis(workspaceId, contentRefId)} />
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
      <p className="m-0 text-xs text-[var(--muted)]">다른 메뉴로 가도 분석은 계속돼요. 끝나면 알려 드려요.</p>
      <Link to={`/workspaces/${workspaceId}/slack`}>← Slack으로 돌아가기</Link>
    </section>
  );
}

export function AnalysisHeader({ status }: { status?: AnalysisStatus }) {
  return (
    <PageHeader
      icon={<NavIcon name="analyses" className="h-[22px] w-[22px] text-[var(--primary)]" />}
      title="논의 정리"
      description="대화 속 결정과 다음 할 일을 한곳에서 검토하세요."
      actions={
        status && (
          <span className={`analysis-status status-${status.toLowerCase()}`}>
            <i />
            {STATUS_TEXT[status]}
          </span>
        )
      }
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
  const [historyOpen, setHistoryOpen] = useState(() => window.matchMedia('(min-width: 1100px)').matches);
  const [evidenceTarget, setEvidenceTarget] = useState<{ itemId: number | null } | null>(null);

  const data = analysis.data;
  const editable = !!data && data.status !== 'CONFIRMED' && data.status !== 'ANALYZING';

  // 목록 등에서 아직 분석 중인 결과를 열었으면, 끝날 때까지 몇 초마다 다시 불러온다.
  const analyzing = data?.status === 'ANALYZING';
  useEffect(() => {
    if (!analyzing) return;
    const timer = setInterval(() => analysis.reload(), 3000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [analyzing]);

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
    <section className="stack analysis-page">
      <AnalysisHeader status={data?.status} />

      {data?.status === 'FAILED' && (
        <div className="alert alert-error">
          AI 분석에 실패했습니다. Slack 스레드에서 다시 "논의 분석하기"를 실행하거나, 아래에서 직접 작성할 수 있습니다.
        </div>
      )}

      <div className="analysis-toolbar">
        <button
          type="button"
          className={`analysis-toolbar-button${historyOpen ? ' is-active' : ''}`}
          aria-expanded={historyOpen}
          aria-controls="analysis-history-panel"
          onClick={() => setHistoryOpen((open) => !open)}
        >
          <AnalysisIcon name="list" />
          분석 내역
        </button>
        <span className="analysis-toolbar-hint">정리 결과를 읽고, 필요한 근거를 확인해 보세요.</span>
        {data && (
          <button
            type="button"
            className="analysis-toolbar-button"
            aria-haspopup="dialog"
            onClick={() => setEvidenceTarget({ itemId: null })}
          >
            <AnalysisIcon name="quote" />
            원문 보기 <span>{data.messages.length}</span>
          </button>
        )}
      </div>
      <div className={`analysis-board${historyOpen ? '' : ' is-history-closed'}`}>
        <div id="analysis-history-panel" className="analysis-history-region" hidden={!historyOpen}>
          <AnalysisHistory workspaceId={workspaceId} selectedId={analysisId} refreshKey={historyTick} />
        </div>

        <div className="card analysis-main">
          {analysis.loading && !data && <Loading />}
          {analysis.error && <ErrorBox error={analysis.error} onRetry={analysis.reload} />}
          {data && (
            <>
              <div className="analysis-main-head">
                <div className="analysis-document-source">
                  <ServiceLogo service="slack" />
                  <span>Slack 대화에서 정리한 논의</span>
                  {data.auto && <span className="analysis-meta-pill">자동 정리</span>}
                </div>
                <h2 className="!m-0 !text-lg !font-extrabold">{data.threadTitle ?? '제목 없는 스레드'}</h2>
                <div className="analysis-document-meta">
                  <span>{formatDateTime(data.createdAt)} 정리</span>
                  <span>·</span>
                  <span>항목 {data.items.length}개</span>
                  {data.analyzedUntil && (
                    <span title={`${formatDateTime(data.analyzedUntil)} 메시지까지 분석`}>· 분석 시점 기준</span>
                  )}
                </div>
                <nav className="analysis-outline" aria-label="정리 항목">
                  {ITEM_TYPES.map((type) => {
                    const count = data.items.filter((item) => item.itemType === type).length;
                    return count > 0 ? (
                      <a key={type} href={`#analysis-section-${type}`}>
                        <AnalysisIcon name={TYPE_ICON[type]} className="h-3.5 w-3.5" />
                        {ITEM_TYPE_TEXT[type]}
                        <span>{count}</span>
                      </a>
                    ) : null;
                  })}
                </nav>
              </div>

              <div className="analysis-document-body">
                {editable && (
                  <div className="analysis-document-tools">
                    <span>내용을 검토하고 필요한 항목을 보완하세요.</span>
                    <details
                      className="analysis-add-menu"
                      onKeyDown={(event) => {
                        if (event.key === 'Escape') {
                          event.currentTarget.open = false;
                          event.currentTarget.querySelector('summary')?.focus();
                          event.stopPropagation();
                        }
                      }}
                      onBlur={(event) => {
                        if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
                      }}
                    >
                      <summary>
                        <AnalysisIcon name="plus" className="h-3.5 w-3.5" />
                        항목 추가
                      </summary>
                      <div>
                        {ITEM_TYPES.map((type) => (
                          <button
                            type="button"
                            key={type}
                            onClick={(event) => {
                              setAdding(type);
                              event.currentTarget.closest('details')?.removeAttribute('open');
                            }}
                          >
                            <AnalysisIcon name={TYPE_ICON[type]} />
                            {ITEM_TYPE_TEXT[type]}
                          </button>
                        ))}
                      </div>
                    </details>
                  </div>
                )}
                {editable && data.items.length === 0 && (
                  <Empty>추출된 결정사항이 없습니다. 아래에서 직접 작성할 수 있습니다.</Empty>
                )}

                {ITEM_TYPES.map((type) => {
                  const items = data.items.filter((i) => i.itemType === type);
                  if (items.length === 0 && adding !== type) return null;
                  return (
                    <section
                      key={type}
                      id={`analysis-section-${type}`}
                      className={`analysis-section type-${type.toLowerCase()}`}
                    >
                      <div className="flex items-center justify-between">
                        <strong className="text-sm">
                          <AnalysisIcon name={TYPE_ICON[type]} />
                          {ITEM_TYPE_TEXT[type]} <span className="analysis-section-count">{items.length}</span>
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
                              onViewEvidence={() => setEvidenceTarget({ itemId: item.itemId })}
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
                    </section>
                  );
                })}
              </div>

              {actionError && <div className="alert alert-error">{actionError}</div>}

              {editable && (
                <div className="analysis-main-foot">
                  <div className="grow">
                    <div className="text-[13px] font-bold">내용과 근거를 확인한 뒤 확정해 주세요.</div>
                    <div className="text-[11px] text-[var(--muted)]">확정 후 문서·팀 작업·일정을 만들 수 있어요.</div>
                  </div>
                  <button type="button" className={BTN_SUB} onClick={discard} disabled={busy}>
                    정리 취소
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
                <OutputPanel workspaceId={workspaceId} analysis={data} onChanged={analysis.reload} />
              )}
            </>
          )}
        </div>
      </div>
      {data && evidenceTarget && (
        <EvidenceDrawer
          workspaceId={workspaceId}
          data={data}
          itemId={evidenceTarget.itemId}
          editable={editable}
          loading={analysis.loading}
          onChanged={analysis.reload}
          onClose={() => setEvidenceTarget(null)}
        />
      )}
    </section>
  );
}
