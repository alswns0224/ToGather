// UI-08 논의 정리 · 실행 연계 (담당: B)
import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  addItem,
  confirmAnalysis,
  discardAnalysis,
  getAnalyses,
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
import Dialog from '../../components/Dialog';
import Icon, { type IconName } from '../../components/Icon';
import { formatDateTime } from '../../utils/date';
import ItemCard, { ItemEditor } from './components/ItemCard';
import { MeetingDialog, NotionDialog, TaskDialog } from './components/OutputDialogs';
import './analysis.css';

const ITEM_TYPES: ItemType[] = ['DECISION', 'FOLLOW_UP', 'OPEN_ISSUE', 'MEETING_PROPOSAL'];
const TYPE_ICON: Record<ItemType, IconName> = {
  DECISION: 'check',
  FOLLOW_UP: 'task',
  OPEN_ISSUE: 'spark',
  MEETING_PROPOSAL: 'calendar',
};

export function statusClass(status: AnalysisStatus) {
  if (status === 'CONFIRMED') return 'confirmed';
  if (status === 'FAILED') return 'failed';
  if (status === 'NO_CONTENT') return 'muted';
  return '';
}

export default function AnalysisListPage() {
  const { workspaceId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const list = useAsync(() => getAnalyses(workspaceId), [workspaceId]);
  const [query, setQuery] = useState('');

  const selectedId = params.get('id') ? Number(params.get('id')) : (list.data?.[0]?.analysisId ?? null);
  const select = (id: number) => setParams({ id: String(id) }, { replace: true });
  const filtered = (list.data ?? []).filter((a) =>
    `${a.threadTitle ?? ''} ${a.threadPreview ?? ''}`.toLowerCase().includes(query.trim().toLowerCase()),
  );
  const pendingCount = (list.data ?? []).filter((a) => a.status !== 'CONFIRMED').length;

  return (
    <div>
      <section className="page-intro">
        <div>
          <div className="collection-title">논의 정리</div>
          <div className="collection-description">
            AI가 Slack 논의를 분석해 결정사항과 후속 작업을 정리했어요. 확정하기 전에는 외부 서비스에 반영되지 않아요.
          </div>
        </div>
        <span className="analysis-count">
          <Icon name="spark" size={15} />
          검토 대기 {pendingCount}개
        </span>
      </section>

      {list.loading && !list.data && <Loading />}
      {list.error && <ErrorBox error={list.error} onRetry={list.reload} />}
      {list.data && list.data.length === 0 && (
        <Empty icon="spark">
          아직 정리한 논의가 없어요. <Link to={`/workspaces/${workspaceId}/slack`}>대화</Link>에서 스레드를 열고 "논의
          정리"를 눌러 보세요. 끝난 논의는 자동으로 분석되기도 해요.
        </Empty>
      )}

      {list.data && list.data.length > 0 && (
        <section className="discussion-layout">
          <aside className="analysis-list-panel surface">
            <div className="analysis-list-head">
              <div>
                <strong>분석 목록</strong>
                <span>Slack 스레드별 분석 결과</span>
              </div>
              <label>
                <Icon name="search" size={14} />
                <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="스레드 검색" />
              </label>
            </div>
            <div className="analysis-list">
              {filtered.length === 0 && <div className="analysis-list-empty">검색 결과가 없어요.</div>}
              {filtered.map((a) => (
                <button
                  type="button"
                  key={a.analysisId}
                  className={a.analysisId === selectedId ? 'active' : ''}
                  onClick={() => select(a.analysisId)}
                >
                  <span className="analysis-list-top">
                    <strong>{a.threadPreview || '(내용 없음)'}</strong>
                    <span className={`analysis-status ${statusClass(a.status)}`}>{STATUS_TEXT[a.status]}</span>
                  </span>
                  <span className="analysis-channel">
                    {a.threadTitle}
                    {a.auto ? ' · 자동 분석' : ''}
                  </span>
                  <span className="analysis-list-meta">
                    <span>{formatDateTime(a.createdAt)}</span>
                    <span>{a.itemCount}개 항목</span>
                  </span>
                </button>
              ))}
            </div>
          </aside>

          {selectedId && (
            <AnalysisDetail
              key={selectedId}
              workspaceId={workspaceId}
              analysisId={selectedId}
              onListChanged={list.reload}
            />
          )}
        </section>
      )}
    </div>
  );
}

function AnalysisDetail({
  workspaceId,
  analysisId,
  onListChanged,
}: {
  workspaceId: string;
  analysisId: number;
  onListChanged: () => void;
}) {
  const navigate = useNavigate();
  const analysis = useAsync(() => getAnalysis(workspaceId, analysisId), [workspaceId, analysisId]);
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const [adding, setAdding] = useState<ItemType | null>(null);
  const [showThread, setShowThread] = useState(false);
  const [dialog, setDialog] = useState<'confirm' | 'discard' | 'notion' | 'task' | 'meeting' | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const data = analysis.data;
  const editable = !!data && data.status !== 'CONFIRMED' && data.status !== 'ANALYZING';
  const changed = () => {
    analysis.reload();
    onListChanged();
  };

  const run = async (action: () => Promise<unknown>, after?: () => void) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      setDialog(null);
      after?.();
    } catch (e) {
      setActionError((e as Error).message);
      setDialog(null);
    } finally {
      setBusy(false);
    }
  };

  if (analysis.loading && !data) {
    return (
      <section className="analysis-result-panel surface">
        <Loading />
      </section>
    );
  }
  if (analysis.error) {
    return (
      <section className="analysis-result-panel surface" style={{ padding: 16 }}>
        <ErrorBox error={analysis.error} onRetry={analysis.reload} />
      </section>
    );
  }
  if (!data) return null;

  return (
    <section className="analysis-result-panel surface">
      <ResultHead data={data} workspaceId={workspaceId} showThread={showThread} onToggleThread={() => setShowThread((s) => !s)} />
      {showThread && (
        <div className="thread-messages">
          {data.messages.map((m) => (
            <div key={m.contentRefId} className={m.analyzed ? '' : 'new'}>
              <strong>{m.authorName ?? '?'}</strong> <span className="editor-hint">{m.createdAt && formatDateTime(m.createdAt)}</span>
              <div style={{ whiteSpace: 'pre-wrap' }}>{m.text}</div>
            </div>
          ))}
        </div>
      )}

      <div className="analysis-items">
        {data.status === 'FAILED' && (
          <div className="notice error" style={{ marginBottom: 14 }}>
            AI 분석에 실패했어요. 대화 화면에서 다시 "논의 정리"를 실행하거나, 아래에서 직접 작성할 수 있어요.
          </div>
        )}
        {editable && data.items.length === 0 && (
          <div className="notice info" style={{ marginBottom: 14 }}>
            추출된 결정사항이 없어요. 각 항목의 "추가"로 직접 작성할 수 있어요.
          </div>
        )}
        {actionError && <div className="notice error" style={{ marginBottom: 14 }}>{actionError}</div>}

        {ITEM_TYPES.map((type) => {
          const items = data.items.filter((i) => i.itemType === type);
          if (!editable && items.length === 0) return null;
          return (
            <div className="analysis-group" key={type}>
              <div className={`analysis-group-title${type === 'OPEN_ISSUE' ? ' unresolved' : ''}`}>
                <span>
                  <Icon name={TYPE_ICON[type]} size={15} />
                </span>
                <strong>{ITEM_TYPE_TEXT[type]}</strong>
                <small>{items.length}</small>
                {editable && adding !== type && (
                  <button type="button" className="add-item" onClick={() => setAdding(type)}>
                    <Icon name="plus" size={13} />
                    추가
                  </button>
                )}
              </div>
              <div className="analysis-group-items">
                {items.length === 0 && adding !== type && <div className="analysis-group-empty">항목이 없어요.</div>}
                {items.map((item) => (
                  <ItemCard
                    key={item.itemId}
                    workspaceId={workspaceId}
                    item={item}
                    editable={editable}
                    members={members.data ?? []}
                    messages={data.messages}
                    onChanged={changed}
                  />
                ))}
                {adding === type && (
                  <article className="analysis-item">
                    <div className="analysis-item-main">
                      <ItemEditor
                        itemType={type}
                        members={members.data ?? []}
                        onCancel={() => setAdding(null)}
                        onSubmit={async (form) => {
                          await addItem(workspaceId, data.analysisId, { ...form, itemType: type });
                          setAdding(null);
                          changed();
                        }}
                      />
                    </div>
                  </article>
                )}
              </div>
            </div>
          );
        })}
      </div>

      <div className="analysis-footer">
        {editable ? (
          <>
            <div>
              <strong>분석 결과를 모두 검토하셨나요?</strong>
              <span>확정하면 Notion 문서, Task, 회의 일정을 만들 수 있어요.</span>
            </div>
            <div className="analysis-create-actions">
              <button type="button" className="ui-button secondary" onClick={() => setDialog('discard')} disabled={busy}>
                취소 (반영 안 함)
              </button>
              <button
                type="button"
                className="ui-button primary"
                onClick={() => setDialog('confirm')}
                disabled={busy || data.items.length === 0}
              >
                <Icon name="check" size={15} />
                분석 확정
              </button>
            </div>
          </>
        ) : data.status === 'CONFIRMED' ? (
          <>
            <div>
              <strong>확정된 분석 결과</strong>
              <span>필요한 후속 항목을 바로 만들어 보세요. 실패한 단계는 다시 시도할 수 있어요.</span>
            </div>
            <div className="analysis-create-actions">
              <button type="button" className="ui-button secondary" onClick={() => setDialog('notion')} disabled={data.notionOutputStatus === 'SUCCESS'}>
                <Icon name="doc" size={15} />
                {data.notionOutputStatus === 'SUCCESS' ? '문서 생성됨' : 'Notion 문서 생성'}
              </button>
              <button type="button" className="ui-button secondary" onClick={() => setDialog('task')}>
                <Icon name="task" size={15} />
                Task 생성
              </button>
              <button type="button" className="ui-button primary" onClick={() => setDialog('meeting')}>
                <Icon name="calendar" size={15} />
                회의 등록
              </button>
            </div>
          </>
        ) : (
          <div>
            <strong>분석 중이에요</strong>
            <span>잠시 후 다시 확인해 주세요.</span>
          </div>
        )}
      </div>

      {dialog === 'confirm' && (
        <ConfirmDialog
          title="분석 확정"
          message="검토를 마치고 확정할까요? 확정한 뒤에는 항목을 수정할 수 없어요."
          action="확정"
          busy={busy}
          onClose={() => setDialog(null)}
          onConfirm={() => run(() => confirmAnalysis(workspaceId, data.analysisId), changed)}
        />
      )}
      {dialog === 'discard' && (
        <ConfirmDialog
          title="정리 결과 취소"
          message="정리 결과를 버릴까요? 외부 서비스에는 아무것도 반영되지 않아요."
          action="버리기"
          danger
          busy={busy}
          onClose={() => setDialog(null)}
          onConfirm={() =>
            run(
              () => discardAnalysis(workspaceId, data.analysisId),
              () => {
                onListChanged();
                navigate(`/workspaces/${workspaceId}/slack/analyses`, { replace: true });
              },
            )
          }
        />
      )}
      {dialog === 'notion' && (
        <NotionDialog
          workspaceId={workspaceId}
          analysisId={data.analysisId}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            changed();
          }}
        />
      )}
      {dialog === 'task' && (
        <TaskDialog
          workspaceId={workspaceId}
          items={data.items.filter((i) => i.itemType === 'FOLLOW_UP')}
          members={members.data ?? []}
          onClose={() => setDialog(null)}
          onChanged={changed}
        />
      )}
      {dialog === 'meeting' && (
        <MeetingDialog
          workspaceId={workspaceId}
          items={data.items.filter((i) => i.itemType === 'MEETING_PROPOSAL')}
          members={members.data ?? []}
          onClose={() => setDialog(null)}
          onChanged={changed}
        />
      )}
    </section>
  );
}

function ResultHead({
  data,
  workspaceId,
  showThread,
  onToggleThread,
}: {
  data: AnalysisResultDTO;
  workspaceId: string;
  showThread: boolean;
  onToggleThread: () => void;
}) {
  return (
    <div className="analysis-result-head">
      <div>
        <span>
          {data.threadTitle} · {formatDateTime(data.createdAt)}
          {data.auto ? ' · 자동 분석' : ''}
        </span>
        <strong>{data.threadPreview || '(내용 없음)'}</strong>
        <p>
          {data.analyzedUntil
            ? `${formatDateTime(data.analyzedUntil)} 메시지까지 분석했어요.`
            : '아직 분석한 메시지가 없어요.'}
        </p>
        <div className="head-links">
          <button type="button" className="text-button" onClick={onToggleThread}>
            {showThread ? '대화 접기' : `대화 보기 (${data.messages.length})`}
          </button>
          <Link to={`/workspaces/${workspaceId}/slack?channel=${data.channelId}`}>
            <Icon name="chat" size={13} />
            대화 화면
          </Link>
          {data.threadLink && (
            <a href={data.threadLink} target="_blank" rel="noreferrer">
              <Icon name="external" size={13} />
              Slack 원본
            </a>
          )}
          {data.notionOutputStatus === 'SUCCESS' && data.notionPageRefId && (
            <Link to={`/workspaces/${workspaceId}/notion?page=${data.notionPageRefId}`}>
              <Icon name="doc" size={13} />
              생성된 Notion 문서
            </Link>
          )}
        </div>
      </div>
      <span className={`analysis-status large ${statusClass(data.status)}`}>
        <Icon name={data.status === 'CONFIRMED' ? 'check' : 'clock'} size={13} />
        {STATUS_TEXT[data.status]}
      </span>
    </div>
  );
}

function ConfirmDialog({
  title,
  message,
  action,
  danger,
  busy,
  onConfirm,
  onClose,
}: {
  title: string;
  message: string;
  action: string;
  danger?: boolean;
  busy: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Dialog
      title={title}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="ui-button secondary" onClick={onClose} disabled={busy}>
            취소
          </button>
          <button type="button" className={`ui-button ${danger ? 'danger' : 'primary'}`} onClick={onConfirm} disabled={busy}>
            {action}
          </button>
        </>
      }
    >
      <p style={{ margin: 0, fontSize: 14, color: '#4d5766' }}>{message}</p>
    </Dialog>
  );
}
