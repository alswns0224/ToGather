import { type FormEvent, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { MUTED } from '../../components/ui';
import NavIcon from '../../layouts/NavIcon';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime } from '../../utils/date';
import { uploadDriveFile } from '../drive/api';
import { getIntegrations, type TargetItem } from '../integration/api';
import { getMembers } from '../workspace/api';
import { STATUS_TEXT as MEETING_STATUS } from '../meeting/MeetingListPage';
import { SOURCES } from './TaskContextPanels';
import {
  getReferenceCandidates,
  getTaskContext,
  linkTaskReference,
  submitTaskDeliverable,
  unlinkTaskReference,
  unsubmitTaskDeliverable,
  type TaskContext,
  type TaskContextContentState,
  type TaskDeliverable,
  type TaskReference,
  type TaskReferenceCandidate,
  type TaskReferenceProvider,
} from './api';

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
  const [contextRevision, setContextRevision] = useState(0);
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
  const [sourceFilter, setSourceFilter] = useState<'ALL' | TaskReferenceProvider>('ALL');
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);

  useEffect(() => {
    let active = true;
    setContextError(null);
    getTaskContext(workspaceId, taskId)
      .then(result => { if (active) setContext(result); })
      .catch(error => { if (active) setContextError(errorText(error, 'Task를 불러오지 못했습니다.')); });
    return () => { active = false; };
  }, [workspaceId, taskId, contextRevision]);

  useEffect(() => {
    let active = true;
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
  const shownRefs = sourceFilter === 'ALL' ? references : references.filter((r) => r.provider === sourceFilter);
  const task = context?.task;
  const assignee = task?.assigneeId != null ? members.data?.find((m) => m.userId === task.assigneeId)?.name : null;
  const done = task?.status === 'DONE';

  return (
    <section className="stack">
      <div className="flex items-center justify-between gap-3">
        <Link to={`/workspaces/${workspaceId}/tasks`} className="text-[15px] font-bold text-[var(--muted)] no-underline hover:text-[var(--text)]">
          ← Task 목록으로
        </Link>
        {task && (
          <span className={`text-sm font-bold ${done ? 'text-[var(--success)]' : 'text-[var(--primary)]'}`}>
            {done ? '완료' : '진행 중'} · {task.progress}%
          </span>
        )}
      </div>

      {contextError && (
        <div role="alert" className="alert alert-error">
          <span>{contextError}</span>
          <button type="button" className="btn btn-small" onClick={() => setContextRevision((current) => current + 1)}>
            다시 시도
          </button>
        </div>
      )}
      {actionError && <p role="alert" className="alert alert-error">{actionError}</p>}
      {!context && !contextError && <p className={MUTED}>Task를 불러오는 중...</p>}

      {context && task && (
        <>
          {/* Task 머리 카드 */}
          <div className="card flex flex-col gap-2 !p-6">
            <h1 className="!m-0 flex items-center gap-2.5 !text-[26px] !font-extrabold">
              <NavIcon name="tasks" className="h-6 w-6 text-[var(--primary)]" />
              {task.title}
            </h1>
            <div className="text-[15px] text-[var(--muted)]">
              담당 {assignee ?? '미지정'} · 마감 {task.dueDate ?? '미정'}
              {task.riskLevel !== 'NORMAL' && !done && (
                <span className={`badge ${task.riskLevel === 'DANGER' ? 'badge-danger' : 'badge-warning'} ml-2`}>
                  {task.riskLevel === 'DANGER' ? '마감 위험' : '마감 주의'}
                </span>
              )}
            </div>
            {task.description && <p className="pre m-0 text-sm">{task.description}</p>}
            <div className="mt-1 h-2.5 w-full max-w-[500px] overflow-hidden rounded-full bg-[var(--surface-2)]">
              <div
                className="h-full rounded-full"
                style={{ width: `${task.progress}%`, background: done ? 'var(--success)' : 'var(--brand-grad)' }}
              />
            </div>
          </div>

          {/* 논의 → 결정 → Task */}
          {context.originDecision && (
            <div className="taskctx-origin">
              <div className="text-sm font-bold text-[var(--primary)]">논의 → 결정 → Task 연결 맥락</div>
              <div className="pre text-[15px]">결정: {context.originDecision.content}</div>
              {context.originEvidence.length > 0 && (
                <div className="flex flex-col gap-1">
                  {context.originEvidence.map((r) => {
                    const url = externalUrl(r.deepLink);
                    const text = r.title?.trim() || r.content?.trim().slice(0, 80) || '제목 없음';
                    return (
                      <div key={r.contentRefId} className="text-[13px] text-[var(--muted)]">
                        근거 · {SOURCES[r.provider as TaskReferenceProvider] ?? r.provider} ·{' '}
                        {url ? (
                          <a href={url} target="_blank" rel="noreferrer">
                            {text}
                          </a>
                        ) : (
                          text
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          <div className="taskctx-board">
            {/* 연결된 자료 */}
            <div className="card flex flex-col gap-3 !p-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <strong className="text-[17px]">연결된 자료 ({references.length})</strong>
                <div className="segmented">
                  {(['ALL', 'SLACK', 'NOTION', 'GOOGLE_DRIVE'] as const).map((s) => (
                    <button
                      key={s}
                      type="button"
                      className={sourceFilter === s ? 'active' : ''}
                      onClick={() => setSourceFilter(s)}
                    >
                      {s === 'ALL' ? '전체' : s === 'GOOGLE_DRIVE' ? 'Drive' : SOURCES[s]}
                    </button>
                  ))}
                </div>
              </div>

              {shownRefs.length === 0 && (
                <p className={`m-0 text-sm ${MUTED}`}>
                  {references.length === 0 ? '아직 연결한 자료가 없습니다. 아래에서 찾아 연결해 보세요.' : '이 출처의 자료가 없습니다.'}
                </p>
              )}
              {shownRefs.map((reference) => (
                <RefCard
                  key={reference.contentRefId}
                  reference={reference}
                  state={context.contentStates[reference.contentRefId]}
                  busy={busy}
                  onUnlink={() => void run(() => unlinkTaskReference(workspaceId, taskId, reference.contentRefId))}
                />
              ))}

              <details className="taskctx-link">
                <summary>+ 자료 연결하기</summary>
                <form className="mt-3 flex flex-wrap items-end gap-2" onSubmit={findCandidates}>
                  <select
                    className="input !w-auto"
                    value={provider}
                    onChange={(event) => setProvider(event.target.value as TaskReferenceProvider)}
                    aria-label="자료 출처"
                  >
                    {Object.entries(SOURCES).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                  <input
                    className="input min-w-36 flex-1"
                    placeholder="자료 검색"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                  />
                  <button type="submit" className="btn">
                    검색
                  </button>
                </form>
                {candidateError && <p role="alert" className="text-sm text-[var(--danger)]">{candidateError}</p>}
                {!candidateError && candidates === null && <p className={`text-sm ${MUTED}`}>자료 후보를 불러오는 중...</p>}
                {candidates?.length === 0 && <p className={`text-sm ${MUTED}`}>연결할 수 있는 자료가 없습니다.</p>}
                {candidates && candidates.length > 0 && (
                  <ul className="m-0 mt-2 max-h-72 list-none space-y-1 overflow-y-auto p-0">
                    {candidates.map((reference) => {
                      const title = reference.title?.trim() || '제목 없음';
                      const url = externalUrl(reference.deepLink);
                      return (
                        <li key={reference.contentRefId} className="flex items-center justify-between gap-3 py-1">
                          <div className="min-w-0 break-words text-sm">
                            {url ? (
                              <a href={url} target="_blank" rel="noreferrer">
                                {title}
                              </a>
                            ) : (
                              <span>{title}</span>
                            )}
                            {reference.lastModifiedAt && (
                              <p className={`m-0 text-xs ${MUTED}`}>{reference.lastModifiedAt.replace('T', ' ').slice(0, 16)}</p>
                            )}
                          </div>
                          <button
                            type="button"
                            className="btn btn-small"
                            disabled={busy}
                            aria-label={`${title} 연결`}
                            onClick={() => void run(() => linkTaskReference(workspaceId, taskId, reference.contentRefId))}
                          >
                            연결
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </details>
            </div>

            <div className="flex flex-col gap-4">
              {/* 필수 산출물 */}
              <div className="card flex flex-col gap-3 !p-5">
                <strong className="text-[17px]">필수 산출물 제출</strong>
                {context.deliverables.length === 0 && (
                  <p className={`m-0 text-sm ${MUTED}`}>이 Task에 설정된 산출물이 없습니다.</p>
                )}
                {context.deliverables.length > 0 && (
                  <>
                    {folderError && <p role="alert" className="m-0 text-sm text-[var(--danger)]">{folderError}</p>}
                    {folders?.length === 0 && (
                      <p className={`m-0 text-sm ${MUTED}`}>
                        업로드할 폴더를 <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리</Link>에서 선택해 주세요.
                      </p>
                    )}
                    {folders && folders.length > 1 && (
                      <label className="flex items-center gap-2 text-[13px] text-[var(--muted)]">
                        업로드할 폴더
                        <select
                          className="input !w-auto flex-1"
                          disabled={busy}
                          value={folderId}
                          onChange={(event) => setFolderId(event.target.value)}
                        >
                          {folders.map((folder) => (
                            <option key={folder.id} value={folder.id}>
                              {folder.name ?? folder.id}
                            </option>
                          ))}
                        </select>
                      </label>
                    )}
                    {context.deliverables.map((deliverable) => (
                      <DeliverableCard key={deliverable.deliverableId} {...{ workspaceId, taskId, folderId, deliverable, busy, run }} />
                    ))}
                  </>
                )}
              </div>

              {/* 연결된 회의 */}
              {context.meetings.map((meeting) => (
                <div key={meeting.meetingId} className="taskctx-meeting">
                  <div className="text-sm font-bold text-[var(--warning)]">연결된 회의</div>
                  <div className="text-xl font-extrabold text-[var(--primary)]">{meeting.title}</div>
                  <div className="text-sm text-[var(--warning)]">
                    {MEETING_STATUS[meeting.status]} · {meeting.startAt ? formatDateTime(meeting.startAt) : '회의 시간 미정'}
                  </div>
                  <Link to={`/workspaces/${workspaceId}/meetings/${meeting.meetingId}`} className="btn self-start">
                    회의 상세로 이동 →
                  </Link>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </section>
  );
}

/** 연결된 자료 한 건: 출처 배지 · 제목 · 시각 / 내용 두 줄 / 원본 · 연결 해제 */
function RefCard({
  reference,
  state,
  busy,
  onUnlink,
}: {
  reference: TaskReference;
  state?: TaskContextContentState;
  busy: boolean;
  onUnlink: () => void;
}) {
  const [open, setOpen] = useState(false);
  const title = reference.title?.trim() || reference.content?.trim().slice(0, 100) || '제목 없음';
  const url = !state?.originDeleted ? externalUrl(reference.deepLink) : null;
  const label = reference.provider === 'GOOGLE_DRIVE' ? 'Drive' : SOURCES[reference.provider as TaskReferenceProvider] ?? reference.provider;
  return (
    <article className="taskctx-ref">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <span className="badge flex-shrink-0">{label}</span>
          {url ? (
            <a href={url} target="_blank" rel="noreferrer" className="truncate text-[15px] font-bold text-[var(--text)]">
              {title}
            </a>
          ) : (
            <span className="truncate text-[15px] font-bold">{title}</span>
          )}
        </div>
        <span className="flex-shrink-0 text-[13px] text-[var(--muted)]">{state?.createdAt ? formatDateTime(state.createdAt) : ''}</span>
      </div>
      {state?.originDeleted && (
        <p className="m-0 text-[13px] text-[var(--warning)]">
          {reference.provider === 'GOOGLE_DRIVE'
            ? state.driveTrashed
              ? '휴지통으로 이동됨'
              : '연동 폴더에서 원본을 확인할 수 없음'
            : '원본 삭제됨'}{' '}
          · 보관된 내용을 보여줘요.
        </p>
      )}
      {reference.content?.trim() && (
        <p className={`pre m-0 text-sm text-[var(--muted)] ${open ? '' : 'line-clamp-2'}`}>{reference.content.trim()}</p>
      )}
      <div className="flex items-center gap-3 text-xs">
        {(reference.content?.length ?? 0) > 120 && (
          <button type="button" className="link-button" onClick={() => setOpen((o) => !o)}>
            {open ? '접기' : '더 보기'}
          </button>
        )}
        <span className="flex-1" />
        <button type="button" className="link-button !text-[var(--muted)]" disabled={busy} onClick={onUnlink}>
          연결 해제
        </button>
      </div>
    </article>
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
  const inputRef = useRef<HTMLInputElement>(null);
  const extension = deliverable.ext?.trim().replace(/^\./, '').toLowerCase();
  const url = externalUrl(deliverable.link);

  function submit(chosen: File | null = file) {
    if (!chosen || (!folderId && pendingContentId === null)) return;
    if (extension && !chosen.name.toLowerCase().endsWith(`.${extension}`)) {
      setFileError(`.${extension} 파일만 제출할 수 있습니다.`);
      return;
    }
    setFileError(null);
    void run(async () => {
      const contentId = pendingContentId ?? (await uploadDriveFile(workspaceId, folderId, chosen)).contentRefId;
      setPendingContentId(contentId);
      await submitTaskDeliverable(workspaceId, taskId, deliverable.deliverableId, contentId);
      setPendingContentId(null);
      setFile(null);
    });
  }

  return (
    <article className={`taskctx-deliv${deliverable.submitted ? ' done' : ''}`}>
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-[15px] font-bold">
            {deliverable.name}
            {extension && <span className="ml-1 text-xs font-semibold text-[var(--muted)]">.{extension}</span>}
          </div>
          {deliverable.submitted ? (
            url ? (
              <a href={url} target="_blank" rel="noreferrer" className="text-xs">
                ✓ {deliverable.fileName ?? 'Drive 파일 열기'}
              </a>
            ) : (
              <span className="text-xs text-[var(--success)]">✓ {deliverable.fileName ?? '제출 완료'}</span>
            )
          ) : (
            file && <span className="text-xs text-[var(--muted)]">{file.name}</span>
          )}
        </div>
        {deliverable.submitted ? (
          <button
            type="button"
            className="btn btn-small flex-shrink-0"
            disabled={busy}
            onClick={() => void run(() => unsubmitTaskDeliverable(workspaceId, taskId, deliverable.deliverableId))}
          >
            제출 해제
          </button>
        ) : (
          <>
            <input
              ref={inputRef}
              className="hidden"
              aria-label={`${deliverable.name} 파일 선택`}
              type="file"
              accept={extension ? `.${extension}` : undefined}
              disabled={busy || !folderId}
              onChange={(event) => {
                const chosen = event.target.files?.[0] ?? null;
                setFile(chosen);
                setPendingContentId(null);
                setFileError(null);
                event.target.value = '';
                if (chosen) submit(chosen);
              }}
            />
            <button
              type="button"
              className="btn btn-primary btn-small flex-shrink-0"
              disabled={busy || (!folderId && pendingContentId === null)}
              title={!folderId ? '연동 관리에서 Drive 폴더를 먼저 선택해 주세요.' : 'Drive에 올리고 제출해요'}
              onClick={() => (pendingContentId !== null ? submit() : inputRef.current?.click())}
            >
              {busy && file ? '제출 중...' : pendingContentId === null ? '제출하기' : '다시 시도'}
            </button>
          </>
        )}
      </div>
      {fileError && <p role="alert" className="m-0 text-sm text-[var(--danger)]">{fileError}</p>}
    </article>
  );
}
