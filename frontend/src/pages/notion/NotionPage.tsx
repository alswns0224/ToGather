// UI-05 문서 (Notion) (담당: B) — 팀원 UI 시안(togather_ui.html): 문서 목록 | 문서 | 연결된 작업
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import {
  createPage,
  deletePage,
  getDatabases,
  getPage,
  getPageBlocks,
  getPages,
  syncNotion,
  type NotionDatabaseResponse,
  type NotionPageDetailResponse,
} from './api';
import { getTasksByContent } from '../task/api';
import { useAsync } from '../../hooks/useAsync';
import { useWorkspaceEvents } from '../../hooks/useWorkspaceEvents';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import ServiceLogo from '../../components/ServiceLogo';
import NavIcon from '../../layouts/NavIcon';
import { formatDateTime } from '../../utils/date';
import BlockEditor from './BlockEditor';
import BlockView from './BlockView';
import NotionText from './NotionText';
import './notion.css';

/** 화면을 보고 있을 때 자동 동기화 간격 */
const AUTO_SYNC_MS = 30_000;

export default function NotionPage() {
  const { workspaceId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const pages = useAsync(() => getPages(workspaceId), [workspaceId]);
  const databases = useAsync(() => getDatabases(workspaceId), [workspaceId]);
  const [creating, setCreating] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [lastSynced, setLastSynced] = useState<Date | null>(null);
  const [query, setQuery] = useState('');
  const [onlyDecisions, setOnlyDecisions] = useState(false);

  const q = query.trim().toLowerCase();
  const list = (pages.data ?? []).filter(
    (p) => (!q || (p.title ?? '').toLowerCase().includes(q)) && (!onlyDecisions || p.fromAnalysis),
  );
  const paramId = params.get('page') ? Number(params.get('page')) : null;
  // 시안처럼 항상 문서 하나를 보여준다. 고른 게 없으면 첫 문서.
  const selectedId = paramId ?? list[0]?.contentRefId ?? null;
  const select = (id: number | null) => {
    const p = new URLSearchParams(params);
    if (id === null) p.delete('page');
    else p.set('page', String(id));
    setParams(p, { replace: true });
  };

  /** force: 동기화 버튼을 누른 경우만 true (1분 간격 제한 없이) */
  const sync = async (force = false) => {
    setSyncing(true);
    setSyncError(null);
    try {
      await syncNotion(workspaceId, force);
      setLastSynced(new Date());
    } catch (e) {
      setSyncError((e as Error).message);
    } finally {
      setSyncing(false);
      pages.reload();
    }
  };

  // 다른 팀원이 문서를 바꾸거나 동기화로 바뀐 게 생기면 새로고침 없이 다시 불러온다.
  useWorkspaceEvents(workspaceId, (event) => {
    if (event.provider === 'NOTION') pages.reload();
  });

  // 저장된 목록을 먼저 보여주고 뒤에서 동기화한다.
  const synced = useRef(false);
  useEffect(() => {
    if (synced.current) return;
    synced.current = true;
    void sync();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  // 자동 동기화: 화면을 보고 있으면 주기적으로 Notion 변경을 가져온다. (Notion 웹훅을 켜기 전에도 버튼 없이 반영)
  // 바뀐 게 있으면 서버가 알려서 목록·문서가 다시 불린다. 서버는 1분 안에 다시 동기화하지 않으므로 실제로는 최대 1분 간격.
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      syncNotion(workspaceId)
        .then(() => setLastSynced(new Date()))
        .catch(() => undefined);
    }, AUTO_SYNC_MS);
    return () => window.clearInterval(timer);
  }, [workspaceId]);

  const noDatabase = databases.data && databases.data.length === 0;

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="notion" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="Notion"
        description="팀의 문서를 모으고, 결정과 작업을 이어보세요."
        actions={
          <>
            {databases.data &&
              (noDatabase ? <span className="badge">연결 안 됨</span> : <span className="badge badge-success">● 연결됨</span>)}
            {lastSynced && (
              <span className="text-xs text-[var(--muted)]">마지막 동기화 {formatDateTime(lastSynced)}</span>
            )}
            <button type="button" className="btn !h-[34px]" onClick={() => sync(true)} disabled={syncing}>
              {syncing ? '동기화 중...' : '↻ 동기화'}
            </button>
          </>
        }
      />

      {syncError && <div className="alert alert-error">동기화하지 못했습니다. 저장된 문서를 보여줍니다. ({syncError})</div>}
      {noDatabase && pages.data && pages.data.length === 0 && (
        <Empty>
          연동된 Notion 페이지·데이터베이스가 없습니다.{' '}
          <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리</Link>에서 Notion을 연결하고 가져올 페이지나
          데이터베이스를 선택해 주세요.
        </Empty>
      )}

      <div className="notion-board">
        {/* 왼쪽: 문서 목록 */}
        <div className="card notion-list">
          <div className="flex items-center gap-1.5">
            <strong className="text-[17px]">문서</strong>
            <span className="badge">{pages.data?.length ?? 0}</span>
          </div>
          <input className="input" placeholder="🔍 문서 검색" value={query} onChange={(e) => setQuery(e.target.value)} />
          <div className="segmented notion-tabs">
            <button type="button" className={!onlyDecisions ? 'active' : ''} onClick={() => setOnlyDecisions(false)}>
              전체
            </button>
            <button type="button" className={onlyDecisions ? 'active' : ''} onClick={() => setOnlyDecisions(true)}>
              결정사항
            </button>
          </div>
          {pages.loading && !pages.data && <Loading />}
          {pages.error && <ErrorBox error={pages.error} onRetry={pages.reload} />}
          {pages.data && list.length === 0 && !noDatabase && (
            <div className="text-[13px] text-[var(--muted)]">{q || onlyDecisions ? '맞는 문서가 없습니다.' : '문서가 없습니다.'}</div>
          )}
          <div className="flex flex-col gap-1">
            {list.map((p) => (
              <button
                key={p.contentRefId}
                type="button"
                className={`notion-item${p.contentRefId === selectedId && !creating ? ' active' : ''}`}
                onClick={() => {
                  setCreating(false);
                  select(p.contentRefId);
                }}
              >
                <strong className="text-[15px]">{p.title || '(제목 없음)'}</strong>
                <span className="flex flex-wrap items-center gap-2 text-xs text-[var(--muted)]">
                  {p.fromAnalysis && <span className="badge badge-primary !text-[11px]">결정사항</span>}
                  {p.syncFailed && <span className="badge badge-warning !text-[11px]">동기화 실패</span>}
                  {p.lastModifiedAt && formatDateTime(p.lastModifiedAt)}
                </span>
              </button>
            ))}
          </div>
          <button
            type="button"
            className="btn btn-primary !h-11 w-full"
            onClick={() => setCreating(true)}
            disabled={!!noDatabase}
            title={noDatabase ? '문서를 만들 Notion 페이지나 데이터베이스를 연동 관리에서 선택해 주세요.' : undefined}
          >
            + 새 문서
          </button>
        </div>

        {/* 가운데: 문서 · 새 문서 */}
        {creating && databases.data ? (
          <PageEditor
            title="새 문서"
            databases={databases.data}
            onCancel={() => setCreating(false)}
            onSubmit={async (form) => {
              const created = await createPage(workspaceId, form);
              setCreating(false);
              pages.reload();
              select(created.contentRefId);
            }}
          />
        ) : selectedId ? (
          // 같은 부모 안의 LinkedTasks와 key가 겹치면 React가 화면을 중복으로 남기므로 접두어를 붙인다.
          <PageDetail
            key={`detail-${selectedId}`}
            workspaceId={workspaceId}
            id={selectedId}
            onClose={() => select(null)}
            onChanged={pages.reload}
          />
        ) : (
          <div className="card text-[13px] text-[var(--muted)]">왼쪽에서 문서를 고르면 여기에 내용이 보여요.</div>
        )}

        {/* 오른쪽: 연결된 작업 */}
        {selectedId && !creating ? (
          <LinkedTasks key={`tasks-${selectedId}`} workspaceId={workspaceId} contentRefId={selectedId} />
        ) : (
          <div />
        )}
      </div>
    </section>
  );
}

/** 이 문서를 자료로 연결한 Task */
function LinkedTasks({ workspaceId, contentRefId }: { workspaceId: string; contentRefId: number }) {
  const tasks = useAsync(() => getTasksByContent(workspaceId, contentRefId), [workspaceId, contentRefId]);
  return (
    <div className="flex flex-col gap-4">
      <div className="card notion-side">
        <div className="flex items-center gap-1.5">
          <strong className="text-[17px]">연결된 작업</strong>
          <span className="badge">{tasks.data?.length ?? 0}</span>
        </div>
        {tasks.loading && !tasks.data && <Loading />}
        {tasks.error && <ErrorBox error={tasks.error} onRetry={tasks.reload} />}
        {tasks.data && tasks.data.length === 0 && (
          <div className="text-[13px] text-[var(--muted)]">
            이 문서를 자료로 쓰는 팀 작업이 없어요. 팀 작업의 협업 맥락에서 이 문서를 연결할 수 있어요.
          </div>
        )}
        {tasks.data?.map((t) => (
          <Link key={t.taskId} to={`/workspaces/${workspaceId}/tasks/${t.taskId}`} className="notion-task">
            <strong className="text-sm text-[var(--text)]">☑ {t.title}</strong>
            <div className="mt-2 flex items-center gap-2">
              <div className="h-2 flex-1 overflow-hidden rounded-full bg-[var(--surface)]">
                <div className="h-full rounded-full bg-[image:var(--brand-grad)]" style={{ width: `${t.progress}%` }} />
              </div>
              <span className="text-xs font-bold text-[var(--text)]">{t.progress}%</span>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}

function PageDetail({
  workspaceId,
  id,
  onClose,
  onChanged,
}: {
  workspaceId: string;
  id: number;
  onClose: () => void;
  onChanged: () => void;
}) {
  const page = useAsync(() => getPage(workspaceId, id), [workspaceId, id]);
  // 표·이미지까지 보여주기 위한 블록. 못 가져오면 저장된 글 본문을 보여준다.
  const blocks = useAsync(() => getPageBlocks(workspaceId, id), [workspaceId, id]);
  const [editing, setEditing] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // 수정 중이 아니면, 다른 팀원이 바꾼 내용을 바로 다시 불러온다.
  useWorkspaceEvents(workspaceId, (event) => {
    if (event.provider === 'NOTION' && !editing) {
      page.reload();
      blocks.reload();
    }
  });

  const remove = async () => {
    const message = page.data?.isOriginDeleted
      ? '보관본을 삭제할까요? ToGather에서 완전히 지워지고, 팀 작업에 연결된 자료에서도 빠집니다. 되돌릴 수 없습니다.'
      : '이 문서를 삭제할까요? Notion에서 휴지통으로 이동합니다.';
    if (!window.confirm(message)) return;
    setDeleteError(null);
    try {
      await deletePage(workspaceId, id);
      onChanged();
      onClose();
    } catch (e) {
      setDeleteError((e as Error).message);
    }
  };

  if (page.data && editing) {
    // 블록 단위 편집: 이미지·표가 있는 문서도 글·표·이미지를 고칠 수 있다.
    return (
      <BlockEditor
        workspaceId={workspaceId}
        id={id}
        onCancel={() => setEditing(false)}
        onSaved={() => {
          setEditing(false);
          page.reload();
          blocks.reload();
          onChanged();
        }}
      />
    );
  }

  return (
    <article className="card notion-doc">
      {page.loading && !page.data && <Loading />}
      {page.error && <ErrorBox error={page.error} onRetry={page.reload} />}
      {page.data && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[var(--border-soft)] pb-3">
            <div className="flex items-center gap-2 text-[13px] text-[var(--muted)]">
              문서 /{page.data.fromAnalysis && <span className="badge badge-primary">결정사항</span>}
            </div>
            <div className="flex gap-2">
              {page.data.deepLink && !page.data.isOriginDeleted && (
                <a className="btn btn-small" href={page.data.deepLink} target="_blank" rel="noreferrer">
                  ↗ Notion에서 열기
                </a>
              )}
              {!page.data.isOriginDeleted ? (
                <>
                  <button type="button" className="btn btn-small" onClick={() => setEditing(true)}>
                    수정
                  </button>
                  <button type="button" className="btn btn-small btn-danger" onClick={remove}>
                    삭제
                  </button>
                </>
              ) : (
                <button type="button" className="btn btn-small btn-danger" onClick={remove}>
                  보관본 삭제
                </button>
              )}
            </div>
          </div>

          <h2 className="!m-0 flex items-center gap-2 !text-2xl !font-extrabold">
            <span className="text-[22px]">📄</span>
            {page.data.title || '(제목 없음)'}
          </h2>
          <div className="flex flex-wrap items-center gap-2 text-[13px] text-[var(--muted)]">
            {page.data.lastModifiedAt && <span>{formatDateTime(page.data.lastModifiedAt)} 수정</span>}
            {page.data.fromAnalysis && <span className="badge badge-primary">결정사항</span>}
          </div>

          {page.data.fromAnalysis && (
            <div className="notion-origin">
              <span className="inline-flex h-7 w-7 flex-shrink-0">
                <ServiceLogo service="slack" />
              </span>
              <div>
                <div className="text-sm font-bold">Slack 논의에서 정리한 문서</div>
                <div className="text-xs text-[var(--muted)]">논의 정리에서 확정한 결정사항으로 만들었어요.</div>
              </div>
            </div>
          )}

          {page.data.refreshFailed && (
            <div className="alert alert-error small">Notion에서 최신 내용을 가져오지 못해 저장된 본문을 보여줍니다.</div>
          )}
          {page.data.isOriginDeleted && (
            <div className="alert alert-error small">Notion에서 삭제된 문서입니다. 보관된 사본을 보여줍니다.</div>
          )}
          {blocks.data && !page.data.isOriginDeleted ? (
            <BlockView blocks={blocks.data.blocks} />
          ) : (
            <div className="notion-body">{page.data.body ? <NotionText text={page.data.body} /> : <span className="muted">(본문 없음)</span>}</div>
          )}
          {deleteError && <div className="alert alert-error">삭제하지 못했습니다. {deleteError}</div>}
        </>
      )}
    </article>
  );
}

function PageEditor({
  title,
  initial,
  databases,
  onSubmit,
  onCancel,
}: {
  title: string;
  initial?: NotionPageDetailResponse;
  databases?: NotionDatabaseResponse[];
  onSubmit: (form: { title: string; body: string; databaseId?: string }) => Promise<void>;
  onCancel: () => void;
}) {
  const [form, setForm] = useState({
    title: initial?.title ?? '',
    body: initial?.body ?? '',
    databaseId: databases?.[0]?.databaseId ?? '',
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSubmit({ title: form.title.trim(), body: form.body, databaseId: form.databaseId || undefined });
    } catch (err) {
      setError((err as Error).message); // 입력 내용은 유지
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="form card" onSubmit={submit}>
      <strong className="text-[17px]">{title}</strong>
      {databases && databases.length > 1 && (
        <label>
          만들 위치
          <select
            className="input"
            value={form.databaseId}
            onChange={(e) => setForm({ ...form, databaseId: e.target.value })}
          >
            {databases.map((d) => (
              <option key={d.databaseId} value={d.databaseId}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
      )}
      <label>
        제목
        <input
          className="input"
          required
          maxLength={200}
          value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })}
        />
      </label>
      <label>
        본문
        <textarea
          className="input notion-editor"
          maxLength={20000}
          value={form.body}
          onChange={(e) => setForm({ ...form, body: e.target.value })}
        />
        <span className="small muted"># 제목, - 목록, 1. 번호, [ ] 할 일, &gt; 인용 형식을 Notion 블록으로 바꿉니다.</span>
      </label>
      {error && <div className="alert alert-error">저장하지 못했습니다. {error}</div>}
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn" onClick={onCancel} disabled={saving}>
          취소
        </button>
        <button className="btn btn-primary" disabled={saving || !form.title.trim()}>
          {saving ? '저장 중...' : '저장'}
        </button>
      </div>
    </form>
  );
}
