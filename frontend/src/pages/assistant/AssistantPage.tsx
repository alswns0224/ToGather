// UI-13 AI 업무 보조 (담당: B)
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import {
  approveSchedule,
  approveTask,
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
  type SuggestionDTO,
} from './api';
import { ApiError } from '../../api/client';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import { formatDate, formatDateTime } from '../../utils/date';
import './assistant.css';

type Tab = 'SCHEDULE' | 'TASK' | 'TODO';

export default function AssistantPage() {
  const { workspaceId = '' } = useParams();
  const [tab, setTab] = useState<Tab>('SCHEDULE');
  const suggestions = useAsync(() => getSuggestions(workspaceId), [workspaceId]);
  const todos = useAsync(() => getMyTodoList(workspaceId), [workspaceId]);
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

  const list = (suggestions.data ?? []).filter((s) => s.type === tab);
  const count = (type: Tab) => (suggestions.data ?? []).filter((s) => s.type === type).length;

  return (
    <section className="stack">
      <PageHeader
        eyebrow="AI ASSISTANT"
        title="AI 업무 보조"
        description="최근 14일 동안 내가 쓰거나 멘션된 Slack 메시지와 내 메일에서 일정·할 일을 찾습니다. 승인하기 전에는 아무것도 등록되지 않습니다."
        actions={
          <button className="btn btn-primary" onClick={detect} disabled={detecting}>
            {detecting ? '메시지 살펴보는 중...' : '✨ 새 후보 찾기'}
          </button>
        }
      />
      {notice && <div className={`alert ${notice.ok ? 'alert-success' : 'alert-error'}`}>{notice.text}</div>}

      <div className="segmented">
        <button className={tab === 'SCHEDULE' ? 'active' : ''} onClick={() => setTab('SCHEDULE')}>
          일정 후보 {count('SCHEDULE') > 0 && `(${count('SCHEDULE')})`}
        </button>
        <button className={tab === 'TASK' ? 'active' : ''} onClick={() => setTab('TASK')}>
          할 일 후보 {count('TASK') > 0 && `(${count('TASK')})`}
        </button>
        <button className={tab === 'TODO' ? 'active' : ''} onClick={() => setTab('TODO')}>
          내 할 일
        </button>
      </div>

      {tab !== 'TODO' && (
        <>
          {suggestions.loading && !suggestions.data && <Loading />}
          {suggestions.error && <ErrorBox error={suggestions.error} onRetry={suggestions.reload} />}
          {suggestions.data && list.length === 0 && (
            <Empty>
              {tab === 'SCHEDULE' ? '일정 후보가 없습니다.' : '할 일 후보가 없습니다.'} "새 후보 찾기"를 눌러 보세요.
            </Empty>
          )}
          {list.length > 0 && (
            <ul className="list card">
              {list.map((s) => (
                <li key={s.suggestionId}>
                  <SuggestionItem workspaceId={workspaceId} suggestion={s} onChanged={reload} />
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {tab === 'TODO' && (
        <>
          {todos.loading && !todos.data && <Loading />}
          {todos.error && <ErrorBox error={todos.error} onRetry={todos.reload} />}
          {todos.data && (
            <div className="stack">
              <div className="card stack">
                <h2>담당 Task</h2>
                {todos.data.assignedTasks.length === 0 ? (
                  <p className="small muted">담당한 Task가 없습니다.</p>
                ) : (
                  <ul className="list">
                    {todos.data.assignedTasks.map((t) => (
                      <li key={t.taskId} className="row">
                        <Link to={`/workspaces/${workspaceId}/tasks/${t.taskId}`} className="grow">
                          {t.title}
                        </Link>
                        {t.dueDate && <span className="small muted">마감 {formatDate(t.dueDate)}</span>}
                      </li>
                    ))}
                  </ul>
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

function SuggestionItem({
  workspaceId,
  suggestion,
  onChanged,
}: {
  workspaceId: string;
  suggestion: SuggestionDTO;
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState(suggestion.timeUndetermined);
  const [form, setForm] = useState({
    title: suggestion.title,
    detectedDatetime: suggestion.detectedDatetime,
    dueDate: suggestion.dueDate,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isSchedule = suggestion.type === 'SCHEDULE';

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

  const save = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      await updateSuggestion(workspaceId, suggestion.suggestionId, form);
      setEditing(false);
    });
  };

  const approve = () =>
    run(() =>
      isSchedule
        ? approveSchedule(workspaceId, suggestion.suggestionId)
        : approveTask(workspaceId, suggestion.suggestionId),
    );

  return (
    <div className="stack">
      {editing ? (
        <form className="form" onSubmit={save}>
          <input
            className="input"
            required
            maxLength={200}
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
          />
          {isSchedule ? (
            <label>
              일시
              <input
                type="datetime-local"
                className="input"
                value={form.detectedDatetime?.slice(0, 16) ?? ''}
                onChange={(e) => setForm({ ...form, detectedDatetime: e.target.value ? `${e.target.value}:00` : null })}
              />
            </label>
          ) : (
            <label>
              마감일
              <input
                type="date"
                className="input"
                value={form.dueDate ?? ''}
                onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })}
              />
            </label>
          )}
          <div className="row">
            <span className="spacer" />
            <button type="button" className="btn btn-small" onClick={() => setEditing(false)} disabled={busy}>
              취소
            </button>
            <button className="btn btn-small btn-primary" disabled={busy || !form.title.trim()}>
              저장
            </button>
          </div>
        </form>
      ) : (
        <div className="row">
          <strong>{suggestion.title}</strong>
          {isSchedule &&
            (suggestion.detectedDatetime ? (
              <span className="small">{formatDateTime(suggestion.detectedDatetime)}</span>
            ) : (
              <span className="badge badge-warning">시간 미정</span>
            ))}
          {!isSchedule && suggestion.dueDate && <span className="small">마감 {formatDate(suggestion.dueDate)}</span>}
          <span className="spacer" />
          <button className="btn btn-small" onClick={() => setEditing(true)} disabled={busy}>
            수정
          </button>
          <button className="btn btn-small" onClick={() => run(() => dismissSuggestion(workspaceId, suggestion.suggestionId))} disabled={busy}>
            무시
          </button>
          <button
            className="btn btn-small btn-primary"
            onClick={approve}
            disabled={busy || (isSchedule && !suggestion.detectedDatetime)}
            title={isSchedule && !suggestion.detectedDatetime ? '일시를 먼저 정해 주세요' : undefined}
          >
            {isSchedule ? '캘린더에 등록' : '할 일로 등록'}
          </button>
        </div>
      )}

      <div className="small muted row">
        <span className="badge">{suggestion.sourceProvider === 'GMAIL' ? '메일' : 'Slack'}</span>
        {suggestion.sourceTitle && <span>{suggestion.sourceTitle}</span>}
        {suggestion.sourceAuthor && <span>{suggestion.sourceAuthor}</span>}
        {suggestion.sourceCreatedAt && <span>{formatDateTime(suggestion.sourceCreatedAt)}</span>}
        {suggestion.sourceLink && (
          <a href={suggestion.sourceLink} target="_blank" rel="noreferrer">
            원본
          </a>
        )}
      </div>
      {suggestion.sourceExcerpt && <p className="small muted clamp">“{suggestion.sourceExcerpt}”</p>}
      {error && <div className="alert alert-error small">{error}</div>}
    </div>
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
      <h2>개인 할 일</h2>
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
