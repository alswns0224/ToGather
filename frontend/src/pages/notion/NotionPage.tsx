// UI-05 문서 (Notion) (담당: B)
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import {
  createPage,
  deletePage,
  getDatabases,
  getPage,
  getPages,
  syncNotion,
  updatePage,
  type NotionDatabaseResponse,
  type NotionPageDetailResponse,
} from './api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import ServiceLogo from '../../components/ServiceLogo';
import { formatDateTime } from '../../utils/date';
import './notion.css';

export default function NotionPage() {
  const { workspaceId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const pages = useAsync(() => getPages(workspaceId), [workspaceId]);
  const databases = useAsync(() => getDatabases(workspaceId), [workspaceId]);
  const [creating, setCreating] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  const selectedId = params.get('page') ? Number(params.get('page')) : null;
  const select = (id: number | null) => {
    const p = new URLSearchParams(params);
    if (id === null) p.delete('page');
    else p.set('page', String(id));
    setParams(p, { replace: true });
  };

  const sync = async () => {
    setSyncing(true);
    setSyncError(null);
    try {
      await syncNotion(workspaceId);
    } catch (e) {
      setSyncError((e as Error).message);
    } finally {
      setSyncing(false);
      pages.reload();
    }
  };

  // 저장된 목록을 먼저 보여주고 뒤에서 동기화한다.
  const synced = useRef(false);
  useEffect(() => {
    if (synced.current) return;
    synced.current = true;
    void sync();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  const noDatabase = databases.data && databases.data.length === 0;

  return (
    <section className="stack">
      <PageHeader
        eyebrow="NOTION"
        title="문서"
        description="연동한 Notion 데이터베이스의 문서를 보고 작성합니다. 논의 정리로 만든 결정 문서도 여기에 모입니다."
        actions={
          <>
            <button className="btn btn-small" onClick={sync} disabled={syncing}>
              {syncing ? '동기화 중...' : '새로고침'}
            </button>
            <button className="btn btn-primary" onClick={() => setCreating(true)} disabled={!!noDatabase}>
              + 새 문서
            </button>
          </>
        }
      />

      {syncError && <div className="alert alert-error">동기화하지 못했습니다. 저장된 문서를 보여줍니다. ({syncError})</div>}
      {noDatabase && (
        <Empty>
          연동된 Notion 데이터베이스가 없습니다.{' '}
          <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리</Link>에서 Notion을 연결하고 데이터베이스를
          선택해 주세요.
        </Empty>
      )}

      {creating && databases.data && (
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
      )}

      <div className={`notion-layout${selectedId ? ' with-detail' : ''}`}>
        <div className="card">
          {pages.loading && !pages.data && <Loading />}
          {pages.error && <ErrorBox error={pages.error} onRetry={pages.reload} />}
          {pages.data && pages.data.length === 0 && !noDatabase && <Empty>문서가 없습니다.</Empty>}
          {pages.data && pages.data.length > 0 && (
            <ul className="list">
              {pages.data.map((p) => (
                <li key={p.contentRefId}>
                  <button
                    className={`notion-item${p.contentRefId === selectedId ? ' active' : ''}`}
                    onClick={() => select(p.contentRefId)}
                  >
                    <span className="row">
                      <span className="inline-flex h-5 w-5 flex-shrink-0 overflow-hidden rounded-md border border-slate-200 bg-white">
                        <ServiceLogo service="notion" />
                      </span>
                      <strong>{p.title || '(제목 없음)'}</strong>
                      {p.fromAnalysis && <span className="badge badge-primary">결정사항 정리</span>}
                      {p.syncFailed && <span className="badge badge-warning">동기화 실패</span>}
                    </span>
                    {p.lastModifiedAt && (
                      <span className="small muted">수정 {formatDateTime(p.lastModifiedAt)}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {selectedId && (
          <PageDetail
            key={selectedId}
            workspaceId={workspaceId}
            id={selectedId}
            onClose={() => select(null)}
            onChanged={pages.reload}
          />
        )}
      </div>
    </section>
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
  const [editing, setEditing] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const remove = async () => {
    if (!window.confirm('이 문서를 삭제할까요? Notion에서 휴지통으로 이동합니다.')) return;
    setDeleteError(null);
    try {
      await deletePage(workspaceId, id);
      onChanged();
      onClose();
    } catch (e) {
      setDeleteError((e as Error).message);
    }
  };

  return (
    <article className="card stack">
      {page.loading && !page.data && <Loading />}
      {page.error && <ErrorBox error={page.error} onRetry={page.reload} />}
      {page.data &&
        (editing ? (
          <PageEditor
            title="문서 수정"
            initial={page.data}
            onCancel={() => setEditing(false)}
            bodyLocked={!page.data.bodyEditable}
            onSubmit={async (form) => {
              await updatePage(workspaceId, id, page.data!.bodyEditable ? { title: form.title, body: form.body } : { title: form.title });
              setEditing(false);
              page.reload();
              onChanged();
            }}
          />
        ) : (
          <>
            <div className="row">
              <h2>{page.data.title || '(제목 없음)'}</h2>
              {page.data.fromAnalysis && <span className="badge badge-primary">결정사항 정리</span>}
              <span className="spacer" />
              <button className="btn btn-small" onClick={onClose}>
                닫기
              </button>
            </div>
            <div className="row small muted">
              {page.data.lastModifiedAt && <span>수정 {formatDateTime(page.data.lastModifiedAt)}</span>}
              {page.data.deepLink && !page.data.isOriginDeleted && (
                <a href={page.data.deepLink} target="_blank" rel="noreferrer">
                  Notion에서 열기
                </a>
              )}
            </div>
            {page.data.refreshFailed && (
              <div className="alert alert-error small">Notion에서 최신 내용을 가져오지 못해 저장된 본문을 보여줍니다.</div>
            )}
            {page.data.isOriginDeleted && (
              <div className="alert alert-error small">Notion에서 삭제된 문서입니다. 보관된 사본을 보여줍니다.</div>
            )}
            <div className="pre notion-body">{page.data.body || <span className="muted">(본문 없음)</span>}</div>
            {deleteError && <div className="alert alert-error">삭제하지 못했습니다. {deleteError}</div>}
            {!page.data.isOriginDeleted && (
              <div className="row">
                <button className="btn" onClick={() => setEditing(true)}>
                  수정
                </button>
                <button className="btn btn-danger" onClick={remove}>
                  삭제
                </button>
              </div>
            )}
          </>
        ))}
    </article>
  );
}

function PageEditor({
  title,
  initial,
  databases,
  bodyLocked = false,
  onSubmit,
  onCancel,
}: {
  title: string;
  initial?: NotionPageDetailResponse;
  databases?: NotionDatabaseResponse[];
  /** 본문을 수정할 수 없는 문서 (제목만 수정) */
  bodyLocked?: boolean;
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
      <h2>{title}</h2>
      {databases && databases.length > 1 && (
        <label>
          데이터베이스
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
      {bodyLocked ? (
        <div className="alert alert-error small">
          이미지·표·하위 블록이 있는 문서라 본문은 Notion에서 직접 수정해 주세요. 여기서는 제목만 바꿀 수 있습니다.
        </div>
      ) : (
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
      )}
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
