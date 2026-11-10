import { type FormEvent, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import PageHeader from '../../components/PageHeader';
import { BTN_AI, BTN_SUB, CARD, INPUT, MUTED, SUB_CARD } from '../../components/ui';
import { uploadDriveFile } from '../drive/api';
import { getIntegrations, type TargetItem } from '../integration/api';
import {
  getReferenceCandidates,
  getTaskContext,
  linkTaskReference,
  submitTaskDeliverable,
  unlinkTaskReference,
  unsubmitTaskDeliverable,
  type TaskContext,
  type TaskDeliverable,
  type TaskReference,
  type TaskReferenceCandidate,
  type TaskReferenceProvider,
} from './api';

const SOURCES: Record<TaskReferenceProvider, string> = {
  SLACK: 'Slack',
  NOTION: 'Notion',
  GOOGLE_DRIVE: 'Google Drive',
};

const displayName = (reference: TaskReference) =>
  reference.title?.trim() || reference.content?.trim().slice(0, 100) || '제목 없음';

const externalUrl = (url: string | null) => url && /^https?:\/\//i.test(url) ? url : null;

const errorText = (error: unknown, fallback: string) => error instanceof Error ? error.message : fallback;

export default function TaskContextPage() {
  const { workspaceId, taskId } = useParams();
  if (!workspaceId || !taskId) {
    return <p className="alert alert-error">Task를 찾을 수 없습니다.</p>;
  }
  return <TaskResources key={`${workspaceId}/${taskId}`} workspaceId={workspaceId} taskId={taskId} />;
}

function TaskResources({ workspaceId, taskId }: { workspaceId: string; taskId: string }) {
  const [context, setContext] = useState<TaskContext | null>(null);
  const [contextError, setContextError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [provider, setProvider] = useState<TaskReferenceProvider>('SLACK');
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [revision, setRevision] = useState(0);
  const [candidates, setCandidates] = useState<TaskReferenceCandidate[] | null>(null);
  const [candidateError, setCandidateError] = useState<string | null>(null);
  const [folders, setFolders] = useState<TargetItem[] | null>(null);
  const [folderError, setFolderError] = useState<string | null>(null);
  const [folderId, setFolderId] = useState('');

  useEffect(() => {
    let active = true;
    getTaskContext(workspaceId, taskId)
      .then(result => { if (active) setContext(result); })
      .catch(error => { if (active) setContextError(errorText(error, 'Task를 불러오지 못했습니다.')); });
    getIntegrations(workspaceId)
      .then(integrations => {
        if (!active) return;
        const selected = integrations.find(integration => integration.provider === 'GOOGLE_DRIVE')
          ?.connections.filter(connection => connection.status === 'CONNECTED')
          .flatMap(connection => connection.targets) ?? [];
        const unique = [...new Map(selected.map(folder => [folder.id, folder])).values()];
        setFolders(unique);
        setFolderId(unique[0]?.id ?? '');
      })
      .catch(error => { if (active) setFolderError(errorText(error, 'Drive 폴더를 불러오지 못했습니다.')); });
    return () => { active = false; };
  }, [workspaceId, taskId]);

  useEffect(() => {
    let active = true;
    setCandidates(null);
    setCandidateError(null);
    getReferenceCandidates(workspaceId, taskId, provider, query)
      .then(result => { if (active) setCandidates(result); })
      .catch(error => { if (active) setCandidateError(errorText(error, '자료 후보를 불러오지 못했습니다.')); });
    return () => { active = false; };
  }, [workspaceId, taskId, provider, query, revision]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      setContext(await getTaskContext(workspaceId, taskId));
      setRevision(current => current + 1);
    } catch (error) {
      setActionError(errorText(error, '변경 사항을 처리하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  }

  function findCandidates(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setQuery(search.trim());
    setRevision(current => current + 1);
  }

  const references = context ? Object.values(context.contentsByProvider).flat() : [];

  return (
    <section className="space-y-6">
      <PageHeader
        eyebrow="TASK RESOURCES"
        title={context?.task.title ?? 'Task 자료·산출물'}
        description={context ? `${context.task.status === 'DONE' ? '완료' : '진행 중'} · 진행률 ${context.task.progress}%` : undefined}
        actions={<Link className={BTN_SUB} to={`/workspaces/${workspaceId}/tasks`}>Task 목록</Link>}
      />
      {contextError && <p role="alert" className="alert alert-error">{contextError}</p>}
      {actionError && <p role="alert" className="alert alert-error">{actionError}</p>}
      {!context && !contextError && <p className={MUTED}>Task를 불러오는 중...</p>}
      {context && (
        <div className="grid gap-5 xl:grid-cols-2">
          <section className={`${CARD} space-y-4 p-6`}>
            <h2 className="m-0 text-lg">관련 자료</h2>
            {references.length === 0 && <p className={`text-sm ${MUTED}`}>아직 연결한 자료가 없습니다.</p>}
            <ul className="m-0 list-none space-y-2 p-0">
              {references.map(reference => {
                const url = externalUrl(reference.deepLink);
                return (
                  <li key={reference.contentRefId} className={`${SUB_CARD} flex items-center justify-between gap-3 p-3`}>
                    <div className="min-w-0 break-words">
                      <p className={`m-0 text-xs ${MUTED}`}>{SOURCES[reference.provider as TaskReferenceProvider] ?? reference.provider}</p>
                      {url
                        ? <a href={url} target="_blank" rel="noreferrer">{displayName(reference)}</a>
                        : <span>{displayName(reference)}</span>}
                    </div>
                    <button type="button" className={BTN_SUB} disabled={busy}
                      onClick={() => void run(() => unlinkTaskReference(workspaceId, taskId, reference.contentRefId))}>
                      연결 해제
                    </button>
                  </li>
                );
              })}
            </ul>
            <form className="flex flex-wrap items-end gap-2" onSubmit={findCandidates}>
              <label className="block">
                <span className={`mb-1 block text-xs ${MUTED}`}>자료 출처</span>
                <select className={INPUT} value={provider} onChange={event => setProvider(event.target.value as TaskReferenceProvider)}>
                  {Object.entries(SOURCES).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                </select>
              </label>
              <label className="min-w-36 flex-1">
                <span className={`mb-1 block text-xs ${MUTED}`}>자료 검색</span>
                <input className={INPUT} value={search} onChange={event => setSearch(event.target.value)} />
              </label>
              <button type="submit" className={BTN_SUB}>검색</button>
            </form>
            {candidateError && <p role="alert" className="text-sm text-rose-600">{candidateError}</p>}
            {!candidateError && candidates === null && <p className={`text-sm ${MUTED}`}>자료 후보를 불러오는 중...</p>}
            {candidates?.length === 0 && <p className={`text-sm ${MUTED}`}>연결할 수 있는 자료가 없습니다.</p>}
            {candidates && candidates.length > 0 && (
              <ul className="m-0 max-h-72 list-none space-y-2 overflow-y-auto p-0">
                {candidates.map(reference => {
                  const title = reference.title?.trim() || '제목 없음';
                  const url = externalUrl(reference.deepLink);
                  return (
                    <li key={reference.contentRefId} className="flex items-center justify-between gap-3 py-1">
                      <div className="min-w-0 break-words text-sm">
                        {url ? <a href={url} target="_blank" rel="noreferrer">{title}</a> : <span>{title}</span>}
                        {reference.lastModifiedAt && (
                          <p className={`m-0 text-xs ${MUTED}`}>{reference.lastModifiedAt.replace('T', ' ').slice(0, 16)}</p>
                        )}
                      </div>
                      <button type="button" className={BTN_SUB} disabled={busy}
                        aria-label={`${title} 연결`}
                        onClick={() => void run(() => linkTaskReference(workspaceId, taskId, reference.contentRefId))}>
                        연결
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
          <section className={`${CARD} space-y-4 p-6`}>
            <h2 className="m-0 text-lg">필수 산출물</h2>
            {context.deliverables.length === 0 && <p className={`text-sm ${MUTED}`}>이 Task에 설정된 산출물이 없습니다.</p>}
            {context.deliverables.length > 0 && (
              <>
                {folderError && <p role="alert" className="text-sm text-rose-600">{folderError}</p>}
                {!folderError && folders === null && <p className={`text-sm ${MUTED}`}>Drive 폴더를 불러오는 중...</p>}
                {folders?.length === 0 && (
                  <p className={`text-sm ${MUTED}`}>
                    업로드할 폴더를 <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리</Link>에서 선택해 주세요.
                  </p>
                )}
                {folders && folders.length > 0 && (
                  <label className="block">
                    <span className={`mb-1 block text-xs ${MUTED}`}>업로드할 Google Drive 폴더</span>
                    <select className={INPUT} disabled={busy} value={folderId} onChange={event => setFolderId(event.target.value)}>
                      {folders.map(folder => <option key={folder.id} value={folder.id}>{folder.name ?? folder.id}</option>)}
                    </select>
                  </label>
                )}
                {context.deliverables.map(deliverable => (
                  <DeliverableCard key={deliverable.deliverableId} {...{ workspaceId, taskId, folderId, deliverable, busy, run }} />
                ))}
              </>
            )}
          </section>
        </div>
      )}
    </section>
  );
}

function DeliverableCard({
  workspaceId, taskId, folderId, deliverable, busy, run,
}: {
  workspaceId: string;
  taskId: string;
  folderId: string;
  deliverable: TaskDeliverable;
  busy: boolean;
  run: (action: () => Promise<void>) => Promise<void>;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [pendingContentId, setPendingContentId] = useState<number | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const extension = deliverable.ext?.trim().replace(/^\./, '').toLowerCase();
  const url = externalUrl(deliverable.link);

  function submit() {
    if (!file || (!folderId && pendingContentId === null)) return;
    if (extension && !file.name.toLowerCase().endsWith(`.${extension}`)) {
      setFileError(`.${extension} 파일만 제출할 수 있습니다.`);
      return;
    }
    setFileError(null);
    void run(async () => {
      const contentId = pendingContentId ?? (await uploadDriveFile(workspaceId, folderId, file)).contentRefId;
      setPendingContentId(contentId);
      await submitTaskDeliverable(workspaceId, taskId, deliverable.deliverableId, contentId);
      setPendingContentId(null);
      setFile(null);
    });
  }

  return (
    <article className={`${SUB_CARD} space-y-2 p-4`}>
      <h3 className="m-0 text-sm font-bold">{deliverable.name}</h3>
      <p className={`m-0 text-xs ${MUTED}`}>
        {extension ? `.${extension} · ` : ''}{deliverable.submitted ? '제출 완료' : '미제출'}
      </p>
      {deliverable.submitted ? (
        <div className="flex flex-wrap items-center gap-3">
          {url
            ? <a href={url} target="_blank" rel="noreferrer">{deliverable.fileName ?? 'Drive 파일 열기'}</a>
            : <span>{deliverable.fileName}</span>}
          <button type="button" className={BTN_SUB} disabled={busy}
            onClick={() => void run(() => unsubmitTaskDeliverable(workspaceId, taskId, deliverable.deliverableId))}>
            제출 해제
          </button>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <input aria-label={`${deliverable.name} 파일 선택`} type="file" accept={extension ? `.${extension}` : undefined}
              disabled={busy || !folderId}
              onChange={event => {
                setFile(event.target.files?.[0] ?? null);
                setPendingContentId(null);
                setFileError(null);
              }}
            />
            <button type="button" className={BTN_AI} disabled={busy || !file || (!folderId && pendingContentId === null)}
              onClick={submit}>
              {pendingContentId === null ? 'Drive 업로드 및 제출' : '제출 다시 시도'}
            </button>
          </div>
          {fileError && <p role="alert" className="text-sm text-rose-600">{fileError}</p>}
        </>
      )}
    </article>
  );
}
