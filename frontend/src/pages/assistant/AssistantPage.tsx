// UI-13 AI 업무 보조 (담당: B)
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import {
  approveSchedule,
  approveTask,
  approveTeamTask,
  approveTeamSchedule,
  completePersonalTask,
  createPersonalTask,
  deletePersonalTask,
  detectCandidates,
  dismissSuggestion,
  getMyTodoList,
  getSuggestions,
  reopenPersonalTask,
  updatePersonalTask,
  type PersonalTaskResponse,
  updateSuggestion,
  candidateWhen,
  isDateOnly,
  type SuggestionDTO,
} from './api';
import { ApiError } from '../../api/client';
import { getMembers, type MemberResponse } from '../workspace/api';
import { useLoginUser } from '../../components/RequireAuth';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import ServiceLogo from '../../components/ServiceLogo';
import NavIcon from '../../layouts/NavIcon';
import { formatDate, formatDateTime } from '../../utils/date';
import './assistant.css';

type Tab = 'SCHEDULE' | 'TASK' | 'TODO';

export default function AssistantPage() {
  const { workspaceId = '' } = useParams();
  const [tab, setTab] = useState<Tab>('SCHEDULE');
  const suggestions = useAsync(() => getSuggestions(workspaceId), [workspaceId]);
  const todos = useAsync(() => getMyTodoList(workspaceId), [workspaceId]);
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const [approved, setApproved] = useState<{ text: string; to: string | null; tab?: Tab } | null>(null);
  const [detecting, setDetecting] = useState(false);
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);

  const reload = () => {
    suggestions.reload();
    todos.reload();
  };

  const detect = async () => {
    setDetecting(true);
    setNotice(null);
    try {
      const result = await detectCandidates(workspaceId);
      setNotice({
        ok: true,
        text:
          result.scannedMessages === 0
            ? '새로 살펴볼 메시지가 없습니다.'
            : `메시지 ${result.scannedMessages}개를 살펴보고 후보 ${result.created.length}개를 찾았습니다.`,
      });
      reload();
    } catch (e) {
      setNotice({ ok: false, text: `후보를 찾지 못했습니다. ${(e as Error).message}` });
    } finally {
      setDetecting(false);
    }
  };

  const pending = (suggestions.data ?? []).filter((s) => s.status === 'PENDING');
  const list = pending.filter((s) => s.type === tab);
  const count = (type: Tab) => pending.filter((s) => s.type === type).length;
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selected = list.find((s) => s.suggestionId === selectedId) ?? list[0];
  const todoCount = todos.data ? todos.data.personalTasks.filter((t) => t.status !== 'DONE').length : 0;

  const TABS: [Tab, string, number][] = [
    ['SCHEDULE', '일정 후보', count('SCHEDULE')],
    ['TASK', '할 일 후보', count('TASK')],
    ['TODO', '내 할 일', todoCount],
  ];

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="assistant" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="AI 업무 보조"
        description={
          <>
            Slack·Gmail에서 찾은 일정과 할 일을 확인하고, 필요한 것만 반영하세요.
            <span className="mt-0.5 block text-xs">ⓘ 승인하기 전에는 등록되지 않아요.</span>
          </>
        }
        actions={
          <button className="btn btn-primary" onClick={detect} disabled={detecting}>
            {detecting ? '메시지 살펴보는 중...' : '↻ 새 후보 찾기'}
          </button>
        }
      />
      {notice && <div className={`alert ${notice.ok ? 'alert-success' : 'alert-error'}`}>{notice.text}</div>}
      {approved && (
        <div className="alert alert-success">
          <span>{approved.text}</span>
          {approved.tab && (
            <button type="button" className="btn btn-small" onClick={() => { setTab(approved.tab!); setApproved(null); }}>
              바로 보기
            </button>
          )}
          {approved.to && (
            <Link to={approved.to} className="btn btn-small">
              바로 보기
            </Link>
          )}
        </div>
      )}

      <div className="tg-underline-tabs">
        {TABS.map(([t, label, n]) => (
          <button key={t} type="button" className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
            {label} <span className="badge">{n}</span>
          </button>
        ))}
      </div>

      {tab !== 'TODO' && (
        <>
          {suggestions.loading && !suggestions.data && <Loading />}
          {suggestions.error && <ErrorBox error={suggestions.error} onRetry={suggestions.reload} />}
          {suggestions.data && (
            <div className="assistant-board">
              <div className="flex flex-col gap-4">
                <div className="card flex flex-col gap-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-1.5">
                      <strong className="text-[15px]">{tab === 'SCHEDULE' ? '검토할 일정' : '검토할 할 일'}</strong>
                      <span className="badge">{list.length}</span>
                    </div>
                    <span className="text-xs text-[var(--muted)]">↕ 최근 발견순</span>
                  </div>
                  {list.length === 0 ? (
                    <div className="py-4 text-center text-[13px] text-[var(--muted)]">
                      {tab === 'SCHEDULE' ? '일정 후보가 없습니다.' : '할 일 후보가 없습니다.'} "새 후보 찾기"를 눌러 보세요.
                    </div>
                  ) : (
                    list.map((s) => (
                      <SuggestionCard
                        key={s.suggestionId}
                        suggestion={s}
                        active={s.suggestionId === selected?.suggestionId}
                        onClick={() => setSelectedId(s.suggestionId)}
                      />
                    ))
                  )}
                </div>
                <div className="card flex flex-col gap-2">
                  <strong className="text-sm">후보를 찾는 범위</strong>
                  <div className="flex items-center gap-2 text-[13px]">
                    <span className="inline-flex h-4 w-4">
                      <ServiceLogo service="slack" />
                    </span>
                    내가 작성하거나 멘션된 대화
                  </div>
                  <div className="flex items-center gap-2 text-[13px]">
                    <span className="inline-flex h-4 w-4">
                      <ServiceLogo service="gmail" />
                    </span>
                    내 메일
                  </div>
                  <div className="text-[11px] text-[var(--muted)]">ⓘ 최근 14일의 메시지를 기준으로 정리해요.</div>
                </div>
              </div>

              <div className="card flex flex-col gap-4">
                <div className="flex items-center gap-2">
                  <strong className="text-[17px]">후보 검토</strong>
                  {selected && <span className="badge badge-primary">검토 대기</span>}
                </div>
                {selected ? (
                  <SuggestionReview
                    key={selected.suggestionId}
                    workspaceId={workspaceId}
                    suggestion={selected}
                    members={members.data ?? []}
                    onChanged={reload}
                    onApproved={(where, id) => {
                      setApproved(
                        where === 'PERSONAL'
                          ? { text: '내 할 일에 추가했어요.', to: null, tab: 'TODO' }
                          : where === 'TEAM'
                            ? { text: '팀 Task로 등록했어요. 팀원 모두 Task 목록에서 볼 수 있어요.', to: `/workspaces/${workspaceId}/tasks/${id}` }
                            : where === 'TEAM_CALENDAR'
                              ? { text: '팀 캘린더에 일정을 등록했어요.', to: `/workspaces/${workspaceId}/calendar` }
                              : { text: '내 Google Calendar에 일정을 등록했어요.', to: `/workspaces/${workspaceId}/calendar` },
                      );
                    }}
                  />
                ) : (
                  <div className="text-[13px] text-[var(--muted)]">왼쪽에서 후보를 고르면 여기서 확인하고 승인할 수 있어요.</div>
                )}
              </div>
            </div>
          )}
        </>
      )}

      {tab === 'TODO' && (
        <>
          {todos.loading && !todos.data && <Loading />}
          {todos.error && <ErrorBox error={todos.error} onRetry={todos.reload} />}
          {todos.data && (
            <div className="assistant-board">
              <div className="card flex flex-col gap-3">
                <strong className="text-[15px]">담당 Task</strong>
                {todos.data.assignedTasks.length === 0 ? (
                  <p className="small muted m-0">담당한 Task가 없습니다.</p>
                ) : (
                  todos.data.assignedTasks.map((t) => (
                    <Link
                      key={t.taskId}
                      to={`/workspaces/${workspaceId}/tasks/${t.taskId}`}
                      className="assistant-cand flex items-center justify-between no-underline"
                    >
                      <span className="text-sm font-bold text-[var(--text)]">☑ {t.title}</span>
                      {t.dueDate && <span className="text-xs text-[var(--muted)]">마감 {formatDate(t.dueDate)}</span>}
                    </Link>
                  ))
                )}
              </div>
              <PersonalTodos workspaceId={workspaceId} tasks={todos.data.personalTasks} onChanged={todos.reload} />
            </div>
          )}
        </>
      )}
    </section>
  );
}

function sourceName(s: SuggestionDTO) {
  return s.sourceProvider === 'GMAIL' ? 'Gmail' : 'Slack';
}

/** 왼쪽 후보 카드 */
function SuggestionCard({
  suggestion: s,
  active,
  onClick,
}: {
  suggestion: SuggestionDTO;
  active: boolean;
  onClick: () => void;
}) {
  const when =
    s.type === 'SCHEDULE'
      ? candidateWhen(s.detectedDatetime, { date: formatDate, dateTime: formatDateTime })
      : s.dueDate
        ? `마감 ${formatDate(s.dueDate)}`
        : '마감 미정';
  return (
    <button type="button" className={`assistant-cand${active ? ' active' : ''}`} onClick={onClick}>
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-[13px] font-bold">
          <span className="inline-flex h-4 w-4">
            <ServiceLogo service={s.sourceProvider === 'GMAIL' ? 'gmail' : 'slack'} />
          </span>
          {sourceName(s)}
          {s.sourceAuthor && ` · ${s.sourceAuthor}`}
        </span>
        <span className="text-xs font-bold text-[var(--primary)]">검토 대기</span>
      </div>
      <div className="mt-2 text-base font-extrabold">{s.title}</div>
      <div className="mt-1 text-[13px] text-[var(--muted)]">📅 {when}</div>
      {s.sourceExcerpt && <div className="mt-1 line-clamp-2 text-[13px]">“{s.sourceExcerpt}”</div>}
    </button>
  );
}

/** 오른쪽 검토: 원본 메시지 + 고칠 수 있는 제목·일시(마감일) + 무시 / 승인 */
function SuggestionReview({
  workspaceId,
  suggestion,
  members,
  onChanged,
  onApproved,
}: {
  workspaceId: string;
  suggestion: SuggestionDTO;
  members: MemberResponse[];
  onChanged: () => void;
  onApproved: (where: 'PERSONAL' | 'TEAM' | 'CALENDAR' | 'TEAM_CALENDAR', id: number) => void;
}) {
  const me = useLoginUser();
  /** 할 일 후보: 나만 보는 개인 할 일 / 팀원 모두 보는 팀 Task */
  const [target, setTarget] = useState<'PERSONAL' | 'TEAM'>('PERSONAL');
  const [assigneeId, setAssigneeId] = useState<number>(me.userId);
  /** 일정 후보: 내 Google Calendar / 팀 캘린더 */
  const [participantIds, setParticipantIds] = useState<number[]>([me.userId]);
  const [form, setForm] = useState({
    title: suggestion.title,
    detectedDatetime: suggestion.detectedDatetime,
    dueDate: suggestion.dueDate,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isSchedule = suggestion.type === 'SCHEDULE';
  const dirty =
    form.title !== suggestion.title ||
    form.detectedDatetime !== suggestion.detectedDatetime ||
    form.dueDate !== suggestion.dueDate;

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setError(
        e instanceof ApiError && e.code === 'CONNECTION_NOT_FOUND'
          ? '개인 Google Calendar를 먼저 연동해 주세요. (연동 관리)'
          : (e as Error).message,
      );
    } finally {
      setBusy(false);
    }
  };

  // 고친 내용이 있으면 먼저 저장하고 승인한다.
  const approve = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      if (dirty) await updateSuggestion(workspaceId, suggestion.suggestionId, form);
      if (isSchedule && target === 'TEAM') {
        const r = await approveTeamSchedule(workspaceId, suggestion.suggestionId, participantIds);
        onApproved('TEAM_CALENDAR', r.contentRefId);
      } else if (isSchedule) {
        const r = await approveSchedule(workspaceId, suggestion.suggestionId);
        onApproved('CALENDAR', r.contentRefId);
      } else if (target === 'TEAM') {
        const r = await approveTeamTask(workspaceId, suggestion.suggestionId, assigneeId);
        onApproved('TEAM', r.taskId);
      } else {
        const r = await approveTask(workspaceId, suggestion.suggestionId);
        onApproved('PERSONAL', r.personalTaskId);
      }
    });
  };

  return (
    <form className="flex flex-col gap-3" onSubmit={approve}>
      <div className="rounded-xl bg-[var(--surface-2)] p-4">
        <div className="flex items-center justify-between gap-2 text-[13px] font-bold text-[var(--muted)]">
          <span>
            원본 메시지 · {sourceName(suggestion)}
            {suggestion.sourceAuthor && ` · ${suggestion.sourceAuthor}`}
            {suggestion.sourceCreatedAt && ` · ${formatDateTime(suggestion.sourceCreatedAt)}`}
          </span>
          {suggestion.sourceLink && (
            <a href={suggestion.sourceLink} target="_blank" rel="noreferrer">
              ↗ 원본 보기
            </a>
          )}
        </div>
        {suggestion.sourceTitle && <div className="mt-1 text-xs text-[var(--muted)]">{suggestion.sourceTitle}</div>}
        <div className="pre mt-2 text-sm">“{suggestion.sourceExcerpt ?? suggestion.title}”</div>
      </div>

      <label className="assistant-field">
        <span>{isSchedule ? '일정 제목' : '할 일'}</span>
        <input
          className="input"
          required
          maxLength={200}
          value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })}
        />
      </label>
      {isSchedule ? (
        <>
          <label className="assistant-field">
            <span>날짜 / 시간</span>
            <input
              type="datetime-local"
              className="input"
              value={form.detectedDatetime?.slice(0, 16) ?? ''}
              onChange={(e) => setForm({ ...form, detectedDatetime: e.target.value ? `${e.target.value}:00` : null })}
            />
          </label>
          {isDateOnly(form.detectedDatetime) && (
            <div className="pl-[122px] text-xs text-[var(--warning)]">메일에 시간이 없어 날짜만 채웠어요. 시간을 정해 주세요.</div>
          )}
        </>
      ) : (
        <label className="assistant-field">
          <span>마감일</span>
          <input
            type="date"
            className="input"
            value={form.dueDate ?? ''}
            onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })}
          />
        </label>
      )}
      <div className="assistant-field">
        <span>등록 위치</span>
        {isSchedule ? (
          <div className="segmented assistant-target">
            <button type="button" className={target === 'PERSONAL' ? 'active' : ''} onClick={() => setTarget('PERSONAL')}>
              개인 일정 (내 캘린더)
            </button>
            <button type="button" className={target === 'TEAM' ? 'active' : ''} onClick={() => setTarget('TEAM')}>
              팀 일정 (팀 캘린더)
            </button>
          </div>
        ) : (
          <div className="segmented assistant-target">
            <button type="button" className={target === 'PERSONAL' ? 'active' : ''} onClick={() => setTarget('PERSONAL')}>
              내 할 일 (나만 보기)
            </button>
            <button type="button" className={target === 'TEAM' ? 'active' : ''} onClick={() => setTarget('TEAM')}>
              팀 Task (모두 보기)
            </button>
          </div>
        )}
      </div>
      {isSchedule && target === 'TEAM' && (
        <div className="assistant-field">
          <span>참여자</span>
          <div className="flex flex-wrap gap-1.5">
            {(members.length ? members : [{ userId: me.userId, name: me.name } as MemberResponse]).map((m) => {
              const on = participantIds.includes(m.userId);
              return (
                <button
                  key={m.userId}
                  type="button"
                  className={`badge cursor-pointer border-0 ${on ? 'badge-primary' : ''}`}
                  onClick={() =>
                    setParticipantIds(on ? participantIds.filter((id) => id !== m.userId) : [...participantIds, m.userId])
                  }
                >
                  {on ? '✓ ' : ''}
                  {m.name}
                  {m.userId === me.userId ? ' (나)' : ''}
                </button>
              );
            })}
          </div>
        </div>
      )}
      {!isSchedule && target === 'TEAM' && (
        <label className="assistant-field">
          <span>담당자</span>
          <select className="input" value={assigneeId} onChange={(e) => setAssigneeId(Number(e.target.value))}>
            {(members.length ? members : [{ userId: me.userId, name: me.name } as MemberResponse]).map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.name}
                {m.userId === me.userId ? ' (나)' : ''}
              </option>
            ))}
          </select>
        </label>
      )}

      {error && <div className="alert alert-error small">{error}</div>}

      <div className="flex gap-2">
        <button
          type="button"
          className="btn !h-12 !px-8"
          onClick={() => run(() => dismissSuggestion(workspaceId, suggestion.suggestionId))}
          disabled={busy}
        >
          무시
        </button>
        <button
          className="btn btn-primary !h-12 flex-1"
          disabled={busy || !form.title.trim() || (isSchedule && !form.detectedDatetime)}
          title={isSchedule && !form.detectedDatetime ? '날짜와 시간을 먼저 정해 주세요' : undefined}
        >
          {busy
            ? '처리 중...'
            : isSchedule
              ? target === 'TEAM'
                ? '승인하고 팀 일정 등록'
                : '승인하고 내 일정 등록'
              : target === 'TEAM'
                ? '승인하고 팀 Task 등록'
                : '승인하고 내 할 일 등록'}
        </button>
      </div>
      <div className="text-center text-xs text-[var(--muted)]">
        {isSchedule
          ? target === 'TEAM'
            ? '📅 팀 일정은 팀 캘린더에 등록되고, 일정 화면에서 팀원 모두가 볼 수 있어요.'
            : '📅 개인 일정은 내 Google Calendar에 등록돼요.'
          : target === 'TEAM'
            ? '☑ 팀 Task는 Task 목록에서 팀원 모두가 볼 수 있어요.'
            : '☑ 내 할 일은 "내 할 일" 탭에서 나만 볼 수 있어요.'}
      </div>
    </form>
  );
}

/** 개인 할 일: 추가, 완료·완료 취소, 제목·마감일 수정, 삭제 */
function PersonalTodos({
  workspaceId,
  tasks,
  onChanged,
}: {
  workspaceId: string;
  tasks: PersonalTaskResponse[];
  onChanged: () => void;
}) {
  const [form, setForm] = useState({ title: '', dueDate: '' });
  const [editingId, setEditingId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      onChanged();
      return true;
    } catch (e) {
      setError((e as Error).message);
      return false;
    }
  };

  const add = async (e: FormEvent) => {
    e.preventDefault();
    if (!form.title.trim()) return;
    if (await run(() => createPersonalTask(workspaceId, { title: form.title.trim(), dueDate: form.dueDate || null }))) {
      setForm({ title: '', dueDate: '' });
    }
  };

  return (
    <div className="card stack">
      <strong className="text-[15px]">개인 할 일</strong>
      <form className="row" onSubmit={add}>
        <input
          className="input grow"
          style={{ width: 'auto' }}
          placeholder="할 일 추가"
          maxLength={200}
          value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })}
        />
        <input
          type="date"
          className="input"
          style={{ width: 'auto' }}
          value={form.dueDate}
          onChange={(e) => setForm({ ...form, dueDate: e.target.value })}
        />
        <button className="btn btn-primary btn-small" disabled={!form.title.trim()}>
          추가
        </button>
      </form>
      {error && <div className="alert alert-error">{error}</div>}
      {tasks.length === 0 ? (
        <p className="small muted">개인 할 일이 없습니다.</p>
      ) : (
        <ul className="list">
          {tasks.map((t) =>
            editingId === t.personalTaskId ? (
              <li key={t.personalTaskId}>
                <PersonalTodoEditor
                  task={t}
                  onCancel={() => setEditingId(null)}
                  onSave={async (title, dueDate) => {
                    if (await run(() => updatePersonalTask(workspaceId, t.personalTaskId, { title, dueDate }))) {
                      setEditingId(null);
                    }
                  }}
                />
              </li>
            ) : (
              <li key={t.personalTaskId} className="row">
                <label className="row grow">
                  <input
                    type="checkbox"
                    checked={t.status === 'DONE'}
                    onChange={() =>
                      run(() =>
                        t.status === 'DONE'
                          ? reopenPersonalTask(workspaceId, t.personalTaskId)
                          : completePersonalTask(workspaceId, t.personalTaskId),
                      )
                    }
                  />
                  <span className={t.status === 'DONE' ? 'muted done-text' : ''}>{t.title}</span>
                </label>
                {t.dueDate && <span className="small muted">마감 {formatDate(t.dueDate)}</span>}
                <button className="link-button small" onClick={() => setEditingId(t.personalTaskId)}>
                  수정
                </button>
                <button
                  className="link-button small"
                  onClick={() =>
                    window.confirm(`"${t.title}"을(를) 삭제할까요?`) &&
                    run(() => deletePersonalTask(workspaceId, t.personalTaskId))
                  }
                >
                  삭제
                </button>
              </li>
            ),
          )}
        </ul>
      )}
    </div>
  );
}

function PersonalTodoEditor({
  task,
  onSave,
  onCancel,
}: {
  task: PersonalTaskResponse;
  onSave: (title: string, dueDate: string | null) => Promise<void>;
  onCancel: () => void;
}) {
  const [title, setTitle] = useState(task.title);
  const [dueDate, setDueDate] = useState(task.dueDate ?? '');
  return (
    <form
      className="row"
      onSubmit={(e) => {
        e.preventDefault();
        if (title.trim()) void onSave(title.trim(), dueDate || null);
      }}
    >
      <input className="input grow" style={{ width: 'auto' }} maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} autoFocus />
      <input type="date" className="input" style={{ width: 'auto' }} value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
      <button type="button" className="btn btn-small" onClick={onCancel}>
        취소
      </button>
      <button className="btn btn-primary btn-small" disabled={!title.trim()}>
        저장
      </button>
    </form>
  );
}
