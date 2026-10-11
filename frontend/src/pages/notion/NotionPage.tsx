// UI-05 문서 (Notion) (담당: B)
import { useEffect, useMemo, useRef, useState } from 'react';
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
import Dialog, { StatusTag } from '../../components/Dialog';
import Icon from '../../components/Icon';
import { formatDateTime } from '../../utils/date';
import DocumentBody from './components/DocumentBody';
import './notion.css';

type Tab = 'all' | 'analysis';

export default function NotionPage() {
  const { workspaceId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const pages = useAsync(() => getPages(workspaceId), [workspaceId]);
  const databases = useAsync(() => getDatabases(workspaceId), [workspaceId]);
  const [tab, setTab] = useState<Tab>('all');
  const [query, setQuery] = useState('');
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

  const dbName = useMemo(() => {
    const map = new Map((databases.data ?? []).map((d) => [d.databaseId, d.name]));
    return (id: string) => map.get(id) ?? '데이터베이스';
  }, [databases.data]);

  const list = (pages.data ?? []).filter(
    (p) => (tab === 'all' || p.fromAnalysis) && (p.title ?? '').toLowerCase().includes(query.trim().toLowerCase()),
  );
  const noDatabase = databases.data && databases.data.length === 0;
  const lastSynced = (pages.data ?? []).map((p) => p.lastModifiedAt).filter(Boolean).sort().at(-1);

  return (
    <div>
      <section className="page-intro">
        <div>
          <div className="collection-title">문서</div>
          <div className="collection-description">Notion에 연결된 팀 문서를 한곳에서 확인하세요.</div>
        </div>
        <button type="button" className="ui-button primary" onClick={() => setCreating(true)} disabled={!!noDatabase}>
          <Icon name="plus" size={16} />새 페이지
        </button>
      </section>

      <section className="notion-connection">
        <span className="notion-logo">N</span>
        <div>
          <strong>{noDatabase ? 'Notion 데이터베이스 연동 필요' : 'Notion과 연결됨'}</strong>
          <span>
            {syncError
              ? `동기화 실패 · ${syncError}`
              : syncing
                ? '동기화 중...'
                : lastSynced
                  ? `최근 수정 · ${formatDateTime(lastSynced)}`
                  : '동기화한 문서가 없어요'}
          </span>
        </div>
        <div className="actions">
          {noDatabase ? (
            <Link to={`/workspaces/${workspaceId}/integrations`}>
              <Icon name="link" size={14} />
              서비스 연동
            </Link>
          ) : (
            <button type="button" onClick={sync} disabled={syncing}>
              <Icon name="retry" size={14} />
              새로고침
            </button>
          )}
        </div>
      </section>

      <section className="toolbar surface document-toolbar">
        <div className="tabs">
          <button type="button" className={tab === 'all' ? 'active' : ''} onClick={() => setTab('all')}>
            전체
          </button>
          <button type="button" className={tab === 'analysis' ? 'active' : ''} onClick={() => setTab('analysis')}>
            결정사항 정리
          </button>
        </div>
        <label className="list-search">
          <Icon name="search" size={15} />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="페이지 검색" />
        </label>
      </section>

      <section className="surface document-table">
        <div className="document-table-head">
          <span>페이지</span>
          <span>데이터베이스</span>
          <span>구분</span>
          <span>최종 수정</span>
          <span />
        </div>
        {pages.loading && !pages.data && <Loading rows={4} />}
        {pages.error && (
          <div style={{ padding: 16 }}>
            <ErrorBox error={pages.error} onRetry={pages.reload} />
          </div>
        )}
        {pages.data && list.length === 0 && (
          <Empty icon="doc">
            {noDatabase
              ? '서비스 연동에서 Notion을 연결하고 데이터베이스를 선택해 주세요.'
              : query || tab !== 'all'
                ? '조건에 맞는 문서가 없어요. 검색어나 탭을 바꿔 보세요.'
                : '문서가 없어요. "새 페이지"로 만들어 보세요.'}
          </Empty>
        )}
        {list.map((p) => (
          <button
            type="button"
            key={p.contentRefId}
            className={`document-row${p.contentRefId === selectedId ? ' selected' : ''}`}
            onClick={() => select(p.contentRefId)}
          >
            <span className="document-name">
              <span className="notion-page-icon">
                <Icon name="doc" size={17} />
              </span>
              <span>
                <strong>{p.title || '(제목 없음)'}</strong>
              </span>
            </span>
            <span>{dbName(p.databaseId)}</span>
            <span>
              {p.fromAnalysis && <StatusTag tone="violet">결정사항 정리</StatusTag>}
              {p.syncFailed && <StatusTag tone="warning">동기화 실패</StatusTag>}
            </span>
            <span>{p.lastModifiedAt ? formatDateTime(p.lastModifiedAt) : '-'}</span>
            <Icon name="arrow" size={15} />
          </button>
        ))}
      </section>

      {selectedId && (
        <PreviewPanel
          key={selectedId}
          workspaceId={workspaceId}
          id={selectedId}
          onClose={() => select(null)}
          onChanged={pages.reload}
        />
      )}

      {creating && databases.data && (
        <PageEditor
          title="새 페이지"
          databases={databases.data}
          onClose={() => setCreating(false)}
          onSubmit={async (form) => {
            const created = await createPage(workspaceId, form);
            setCreating(false);
            pages.reload();
            select(created.contentRefId);
          }}
        />
      )}
    </div>
  );
}

/** 오른쪽 페이지 미리보기 패널: 본문, Notion에서 열기, 수정·삭제 */
function PreviewPanel({
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
  const [deleting, setDeleting] = useState(false);
  const data = page.data;

  return (
    <aside className="detail-panel notion-preview-panel">
      <div className="detail-head">
        <div>
          <span>Notion 페이지</span>
          <strong>페이지 미리보기</strong>
        </div>
        <button type="button" className="plain-icon" onClick={onClose} aria-label="닫기">
          <Icon name="close" />
        </button>
      </div>
      <div className="detail-body">
        {page.loading && !data && <Loading rows={3} />}
        {page.error && <ErrorBox error={page.error} onRetry={page.reload} />}
        {data && (
          <>
            {data.refreshFailed && (
              <div className="notice warning">Notion에서 최신 내용을 가져오지 못해 저장된 본문을 보여줘요.</div>
            )}
            {data.isOriginDeleted && <div className="notice error">Notion에서 삭제된 문서예요. 보관된 사본을 보여줘요.</div>}
            <div className="notion-preview-icon">
              <Icon name="doc" size={25} />
            </div>
            <div className="detail-title">{data.title || '(제목 없음)'}</div>
            <div className="notion-byline">
              {data.fromAnalysis && <StatusTag tone="violet">결정사항 정리</StatusTag>}
              <span>{data.lastModifiedAt ? `${formatDateTime(data.lastModifiedAt)} 수정` : ''}</span>
            </div>
            <div className="notion-document-body">
              <DocumentBody text={data.body} />
            </div>
            {data.deepLink && !data.isOriginDeleted && (
              <a className="notion-open-link" href={data.deepLink} target="_blank" rel="noreferrer">
                <span className="notion-logo small">N</span>
                Notion에서 열기
                <Icon name="external" size={15} />
              </a>
            )}
            {!data.isOriginDeleted && (
              <div className="notion-panel-actions">
                <button type="button" className="ui-button danger" onClick={() => setDeleting(true)}>
                  <Icon name="trash" size={15} />
                  삭제
                </button>
                <button type="button" className="ui-button secondary" onClick={() => setEditing(true)}>
                  <Icon name="edit" size={15} />
                  수정
                </button>
              </div>
            )}
          </>
        )}
      </div>

      {editing && data && (
        <PageEditor
          title="페이지 수정"
          initial={data}
          bodyLocked={!data.bodyEditable}
          onClose={() => setEditing(false)}
          onSubmit={async (form) => {
            await updatePage(workspaceId, id, data.bodyEditable ? { title: form.title, body: form.body } : { title: form.title });
            setEditing(false);
            page.reload();
            onChanged();
          }}
        />
      )}
      {deleting && data && (
        <DeleteDialog
          title={data.title ?? ''}
          onClose={() => setDeleting(false)}
          onConfirm={async () => {
            await deletePage(workspaceId, id);
            onChanged();
            onClose();
          }}
        />
      )}
    </aside>
  );
}

function PageEditor({
  title,
  initial,
  databases,
  bodyLocked = false,
  onSubmit,
  onClose,
}: {
  title: string;
  initial?: NotionPageDetailResponse;
  databases?: NotionDatabaseResponse[];
  /** 이미지·표·하위 블록이 있어 본문은 수정할 수 없는 문서 (제목만) */
  bodyLocked?: boolean;
  onSubmit: (form: { title: string; body: string; databaseId?: string }) => Promise<void>;
  onClose: () => void;
}) {
  const [form, setForm] = useState({
    title: initial?.title ?? '',
    body: initial?.body ?? '',
    databaseId: databases?.[0]?.databaseId ?? '',
  });
  const [touched, setTouched] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const titleError = touched && !form.title.trim() ? '제목을 입력해 주세요.' : '';

  const submit = async () => {
    setTouched(true);
    if (!form.title.trim()) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit({ title: form.title.trim(), body: form.body, databaseId: form.databaseId || undefined });
    } catch (e) {
      setError((e as Error).message); // 입력 내용은 유지
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog
      title={title}
      width={560}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="ui-button secondary" onClick={onClose} disabled={saving}>
            취소
          </button>
          <button type="button" className="ui-button primary" onClick={submit} disabled={saving}>
            {saving ? '저장 중...' : '저장'}
          </button>
        </>
      }
    >
      {databases && databases.length > 1 && (
        <label className="field">
          <span>데이터베이스</span>
          <select value={form.databaseId} onChange={(e) => setForm({ ...form, databaseId: e.target.value })}>
            {databases.map((d) => (
              <option key={d.databaseId} value={d.databaseId}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
      )}
      <label className={`field${titleError ? ' has-error' : ''}`}>
        <span>
          제목<b>*</b>
        </span>
        <input maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
        {titleError && <em>{titleError}</em>}
      </label>
      {bodyLocked ? (
        <div className="notice warning">
          이미지·표·하위 블록이 있는 문서라 본문은 Notion에서 직접 수정해 주세요. 여기서는 제목만 바꿀 수 있어요.
        </div>
      ) : (
        <label className="field">
          <span>
            본문<small># 제목, - 목록, 1. 번호, [ ] 할 일, &gt; 인용</small>
          </span>
          <textarea className="tall" maxLength={20000} value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} />
        </label>
      )}
      {error && <div className="notice error">저장하지 못했어요. {error}</div>}
    </Dialog>
  );
}

function DeleteDialog({
  title,
  onConfirm,
  onClose,
}: {
  title: string;
  onConfirm: () => Promise<void>;
  onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };
  return (
    <Dialog
      title="페이지 삭제"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="ui-button secondary" onClick={onClose} disabled={busy}>
            취소
          </button>
          <button type="button" className="ui-button danger" onClick={confirm} disabled={busy}>
            삭제
          </button>
        </>
      }
    >
      <div className="delete-copy">
        <div className="delete-icon">
          <Icon name="trash" />
        </div>
        <div>
          <strong>"{title || '제목 없음'}"을(를) 삭제할까요?</strong>
          <span>Notion에서 휴지통으로 이동해요. 연결된 Task·분석에서는 보관된 사본을 볼 수 있어요.</span>
        </div>
      </div>
      {error && <div className="notice error" style={{ marginTop: 12 }}>삭제하지 못했어요. {error}</div>}
    </Dialog>
  );
}
