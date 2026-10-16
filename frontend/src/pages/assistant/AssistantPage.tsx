// UI-13 AI 보조 (담당: B)
import { useState, type KeyboardEvent } from 'react';
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
  updateSuggestion,
  type PersonalTaskResponse,
  type SuggestionDTO,
} from './api';
import { ApiError } from '../../api/client';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';
import Dialog from '../../components/Dialog';
import Icon from '../../components/Icon';
import { formatDate, formatDateTime, formatTime } from '../../utils/date';
import './assistant.css';

type Tab = 'SCHEDULE' | 'TODO';
type Notice = { ok: boolean; text: string } | null;

export default function AssistantPage() {
  const { workspaceId = '' } = useParams();
  const [tab, setTab] = useState<Tab>('SCHEDULE');
  const suggestions = useAsync(() => getSuggestions(workspaceId), [workspaceId]);
  const todos = useAsync(() => getMyTodoList(workspaceId), [workspaceId]);
  const [detecting, setDetecting] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);

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
            ? '새로 살펴볼 메시지가 없어요.'
            : `메시지 ${result.scannedMessages}개를 살펴보고 후보 ${result.created.length}개를 찾았어요.`,
      });
      reload();
    } catch (e) {
      setNotice({ ok: false, text: `후보를 찾지 못했어요. ${(e as Error).message}` });
    } finally {
      setDetecting(false);
    }
  };

  const schedules = (suggestions.data ?? []).filter((s) => s.type === 'SCHEDULE');
  const taskSuggestions = (suggestions.data ?? []).filter((s) => s.type === 'TASK');
  const openTodos = (todos.data?.personalTasks ?? []).filter((t) => t.status === 'TODO').length;

  return (
    <div>
      <section className="page-intro">
        <div>
          <div className="collection-title">AI 보조</div>
          <div className="collection-description">
            대화와 메일에서 놓치기 쉬운 일정과 할 일을 찾아드려요. 등록하기 전에는 아무것도 반영되지 않아요.
          </div>
        </div>
        <div className="ai-intro-actions">
          <span className="ai-powered">
            <Icon name="spark" size={15} />
            ToGather AI
          </span>
          <button type="button" className="ui-button secondary" onClick={detect} disabled={detecting}>
            <Icon name="retry" size={15} />
            {detecting ? '메시지 살펴보는 중...' : '새 후보 찾기'}
          </button>
        </div>
      </section>

      {notice && (
        <section className={notice.ok ? 'analysis-notice' : 'notice error'} style={{ marginBottom: 12 }}>
          <Icon name={notice.ok ? 'check' : 'alert'} size={16} />
          <span>{notice.text}</span>
          <button type="button" className="plain-icon" onClick={() => setNotice(null)} aria-label="닫기" style={{ marginLeft: 'auto' }}>
            <Icon name="close" size={14} />
          </button>
        </section>
      )}

      <section className="ai-tabs">
        <button type="button" className={tab === 'SCHEDULE' ? 'active' : ''} onClick={() => setTab('SCHEDULE')}>
          <Icon name="calendar" size={16} />
          일정 후보<span>{schedules.length}</span>
        </button>
        <button type="button" className={tab === 'TODO' ? 'active' : ''} onClick={() => setTab('TODO')}>
          <Icon name="task" size={16} />내 할 일<span>{taskSuggestions.length + openTodos}</span>
        </button>
      </section>

      {tab === 'SCHEDULE' ? (
        <section>
          <div className="ai-section-head">
            <div>
              <strong>새로운 일정 후보</strong>
              <span>메일과 메시지에서 일정으로 보이는 내용을 찾았어요.</span>
            </div>
            <span>최근 14일 기준</span>
          </div>
          {suggestions.loading && !suggestions.data && <Loading />}
          {suggestions.error && <ErrorBox error={suggestions.error} onRetry={suggestions.reload} />}
          {suggestions.data &&
            (schedules.length ? (
              <div className="ai-schedule-grid">
                {schedules.map((s) => (
                  <ScheduleCard key={s.suggestionId} workspaceId={workspaceId} suggestion={s} onChanged={reload} onNotice={setNotice} />
                ))}
              </div>
            ) : (
              <div className="surface ai-empty">
                <div>
                  <Icon name="calendar" size={27} />
                </div>
                <strong>새로운 일정 후보가 없어요</strong>
                <span>"새 후보 찾기"를 누르면 최근 메시지와 메일을 살펴봐요.</span>
              </div>
            ))}
        </section>
      ) : (
        <section className="ai-todo-layout">
          <div>
            <div className="ai-section-head">
              <div>
                <strong>팀 Task</strong>
                <span>이 워크스페이스에서 내가 담당한 업무</span>
              </div>
              <Link to={`/workspaces/${workspaceId}/tasks`} className="text-button">
                전체 보기 <Icon name="arrow" size={13} />
              </Link>
            </div>
            <div className="surface ai-task-list">
              {todos.data?.assignedTasks.length ? (
                todos.data.assignedTasks.map((t) => (
                  <div className="ai-task-row" key={t.taskId}>
                    <Link to={`/workspaces/${workspaceId}/tasks/${t.taskId}`} className="ai-task-copy">
                      <strong>{t.title}</strong>
                      <span>{t.dueDate ? `마감 ${formatDate(t.dueDate)}` : '마감 없음'}</span>
                    </Link>
                  </div>
                ))
              ) : (
                <div className="ai-note">담당한 Task가 없어요.</div>
              )}
            </div>

            <PersonalTodos
              workspaceId={workspaceId}
              tasks={todos.data?.personalTasks ?? []}
              loading={todos.loading && !todos.data}
              error={todos.error}
              onRetry={todos.reload}
              onChanged={todos.reload}
            />
          </div>

          <div>
            <div className="ai-section-head">
              <div>
                <strong>할 일 후보</strong>
                <span>AI가 대화와 메일에서 찾아낸 내 할 일</span>
              </div>
              <span>{taskSuggestions.length}개</span>
            </div>
            {taskSuggestions.length ? (
              <div className="todo-suggestion-list">
                {taskSuggestions.map((s) => (
                  <TodoSuggestionCard key={s.suggestionId} workspaceId={workspaceId} suggestion={s} onChanged={reload} onNotice={setNotice} />
                ))}
              </div>
            ) : (
              <div className="surface ai-empty compact">
                <div>
                  <Icon name="check" size={24} />
                </div>
                <strong>모두 정리했어요</strong>
                <span>새로운 할 일 후보가 없어요.</span>
              </div>
            )}
          </div>
        </section>
      )}
    </div>
  );
}

function sourceLabel(s: SuggestionDTO) {
  return s.sourceProvider === 'GMAIL' ? '메일' : '메시지';
}

function useAction(onChanged: () => void, onNotice: (n: Notice) => void) {
  const [busy, setBusy] = useState(false);
  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true);
    onNotice(null);
    try {
      await action();
      onNotice({ ok: true, text: done });
      onChanged();
    } catch (e) {
      const message =
        e instanceof ApiError && e.code === 'CONNECTION_NOT_FOUND'
          ? '개인 Google Calendar를 먼저 연동해 주세요. (서비스 연동)'
          : (e as Error).message;
      onNotice({ ok: false, text: message });
    } finally {
      setBusy(false);
    }
  };
  return { busy, run };
}

function ScheduleCard({
  workspaceId,
  suggestion,
  onChanged,
  onNotice,
}: {
  workspaceId: string;
  suggestion: SuggestionDTO;
  onChanged: () => void;
  onNotice: (n: Notice) => void;
}) {
  const [editing, setEditing] = useState(false);
  const { busy, run } = useAction(onChanged, onNotice);
  const at = suggestion.detectedDatetime;

  return (
    <article className="ai-schedule-card surface">
      <div className="ai-card-top">
        <span className={`ai-source ${suggestion.sourceProvider === 'GMAIL' ? 'mail' : 'message'}`}>
          <Icon name={suggestion.sourceProvider === 'GMAIL' ? 'doc' : 'chat'} size={14} />
          {sourceLabel(suggestion)}
        </span>
        {suggestion.timeUndetermined && (
          <span className="time-undecided">
            <Icon name="clock" size={12} />
            시간 미정
          </span>
        )}
      </div>
      <strong>{suggestion.title}</strong>
      <p>{suggestion.sourceExcerpt ? `“${suggestion.sourceExcerpt}”` : ''}</p>
      <div className="suggestion-datetime">
        <span>
          <Icon name="calendar" size={14} />
          {at ? formatDate(at) : '날짜 미정'}
        </span>
        <span>
          <Icon name="clock" size={14} />
          {at ? formatTime(at) : '시간을 입력해 주세요'}
        </span>
      </div>
      <div className="suggestion-source-copy">
        {suggestion.sourceAuthor ?? '알 수 없음'}님이 보낸 {sourceLabel(suggestion)}에서 발견
        {suggestion.sourceCreatedAt && ` · ${formatDateTime(suggestion.sourceCreatedAt)}`}
        {suggestion.sourceLink && (
          <a className="ai-source-link" href={suggestion.sourceLink} target="_blank" rel="noreferrer">
            원본
          </a>
        )}
      </div>
      <div className="ai-card-actions">
        <button
          type="button"
          className="ui-button ghost"
          disabled={busy}
          onClick={() => run(() => dismissSuggestion(workspaceId, suggestion.suggestionId), '일정 후보를 무시했어요.')}
        >
          무시
        </button>
        <button type="button" className="ui-button secondary" disabled={busy} onClick={() => setEditing(true)}>
          <Icon name="edit" size={14} />
          수정
        </button>
        <button
          type="button"
          className="ui-button primary"
          disabled={busy}
          onClick={() =>
            at
              ? run(() => approveSchedule(workspaceId, suggestion.suggestionId), '개인 캘린더에 일정을 등록했어요.')
              : setEditing(true)
          }
        >
          <Icon name="calendar" size={14} />
          등록
        </button>
      </div>
      {editing && (
        <SuggestionEditor
          suggestion={suggestion}
          onClose={() => setEditing(false)}
          onSave={async (form) => {
            await updateSuggestion(workspaceId, suggestion.suggestionId, form);
            setEditing(false);
            onChanged();
          }}
        />
      )}
    </article>
  );
}

function TodoSuggestionCard({
  workspaceId,
  suggestion,
  onChanged,
  onNotice,
}: {
  workspaceId: string;
  suggestion: SuggestionDTO;
  onChanged: () => void;
  onNotice: (n: Notice) => void;
}) {
  const [editing, setEditing] = useState(false);
  const { busy, run } = useAction(onChanged, onNotice);
  return (
    <article className="surface todo-suggestion-card">
      <div className="todo-suggestion-icon">
        <Icon name="spark" size={16} />
      </div>
      <div>
        <strong>{suggestion.title}</strong>
        <span>
          {suggestion.sourceAuthor ?? '알 수 없음'} · {sourceLabel(suggestion)}
          {suggestion.sourceLink && (
            <a className="ai-source-link" href={suggestion.sourceLink} target="_blank" rel="noreferrer">
              원본
            </a>
          )}
        </span>
        {suggestion.sourceExcerpt && <span className="excerpt">“{suggestion.sourceExcerpt}”</span>}
        <small>
          <Icon name="clock" size={11} />
          {suggestion.dueDate ? `마감 ${formatDate(suggestion.dueDate)}` : '마감 없음'}
        </small>
      </div>
      <div>
        <button
          type="button"
          className="ui-button ghost"
          disabled={busy}
          onClick={() => run(() => dismissSuggestion(workspaceId, suggestion.suggestionId), '할 일 후보를 무시했어요.')}
        >
          무시
        </button>
        <button type="button" className="ui-button secondary" disabled={busy} onClick={() => setEditing(true)}>
          수정
        </button>
        <button
          type="button"
          className="ui-button primary"
          disabled={busy}
          onClick={() => run(() => approveTask(workspaceId, suggestion.suggestionId), '개인 할 일로 등록했어요.')}
        >
          등록
        </button>
      </div>
      {editing && (
        <SuggestionEditor
          suggestion={suggestion}
          onClose={() => setEditing(false)}
          onSave={async (form) => {
            await updateSuggestion(workspaceId, suggestion.suggestionId, form);
            setEditing(false);
            onChanged();
          }}
        />
      )}
    </article>
  );
}

/** 후보 수정: 제목 + 일시(일정) 또는 마감일(할 일) */
function SuggestionEditor({
  suggestion,
  onSave,
  onClose,
}: {
  suggestion: SuggestionDTO;
  onSave: (form: { title: string; detectedDatetime: string | null; dueDate: string | null }) => Promise<void>;
  onClose: () => void;
}) {
  const isSchedule = suggestion.type === 'SCHEDULE';
  const [form, setForm] = useState({
    title: suggestion.title,
    detectedDatetime: suggestion.detectedDatetime,
    dueDate: suggestion.dueDate,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const titleError = !form.title.trim() ? '제목을 입력해 주세요.' : '';

  const save = async () => {
    if (titleError) return;
    setSaving(true);
    setError(null);
    try {
      await onSave({ ...form, title: form.title.trim() });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog
      title={isSchedule ? '일정 후보 수정' : '할 일 후보 수정'}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="ui-button secondary" onClick={onClose} disabled={saving}>
            취소
          </button>
          <button type="button" className="ui-button primary" onClick={save} disabled={saving || !!titleError}>
            저장
          </button>
        </>
      }
    >
      {isSchedule && !suggestion.detectedDatetime && (
        <div className="notice warning" style={{ marginBottom: 14 }}>
          일시가 정해지지 않은 일정이에요. 일시를 입력해야 캘린더에 등록할 수 있어요.
        </div>
      )}
      <label className={`field${titleError ? ' has-error' : ''}`}>
        <span>
          제목<b>*</b>
        </span>
        <input maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
        {titleError && <em>{titleError}</em>}
      </label>
      {isSchedule ? (
        <label className="field">
          <span>일시</span>
          <input
            type="datetime-local"
            value={form.detectedDatetime?.slice(0, 16) ?? ''}
            onChange={(e) => setForm({ ...form, detectedDatetime: e.target.value ? `${e.target.value}:00` : null })}
          />
        </label>
      ) : (
        <label className="field">
          <span>마감일</span>
          <input type="date" value={form.dueDate ?? ''} onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })} />
        </label>
      )}
      {error && <div className="notice error">저장하지 못했어요. {error}</div>}
    </Dialog>
  );
}

/** 개인 할 일: 체크(완료·취소), 제목 바로 수정, 추가, 삭제 */
function PersonalTodos({
  workspaceId,
  tasks,
  loading,
  error,
  onRetry,
  onChanged,
}: {
  workspaceId: string;
  tasks: PersonalTaskResponse[];
  loading: boolean;
  error?: Error;
  onRetry: () => void;
  onChanged: () => void;
}) {
  const [adding, setAdding] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [actionError, setActionError] = useState<string | null>(null);

  const run = async (action: () => Promise<unknown>) => {
    setActionError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setActionError((e as Error).message);
    }
  };

  const add = async () => {
    if (!newTitle.trim()) {
      setAdding(false);
      return;
    }
    await run(() => createPersonalTask(workspaceId, { title: newTitle.trim(), dueDate: null }));
    setNewTitle('');
    setAdding(false);
  };

  return (
    <>
      <div className="ai-section-head personal-head">
        <div>
          <strong>개인 할 일</strong>
          <span>나만 볼 수 있는 간단한 체크리스트</span>
        </div>
        <button type="button" onClick={() => setAdding(true)}>
          <Icon name="plus" size={13} />
          추가
        </button>
      </div>
      {loading && <Loading rows={2} />}
      {error && <ErrorBox error={error} onRetry={onRetry} />}
      {actionError && <div className="notice error" style={{ marginBottom: 8 }}>{actionError}</div>}
      <div className="surface personal-todo-list">
        {adding && (
          <div className="personal-todo adding">
            <span className="ai-checkbox" />
            <input
              autoFocus
              maxLength={200}
              placeholder="할 일을 입력하고 Enter"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              onBlur={add}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.nativeEvent.isComposing) void add();
                if (e.key === 'Escape') setAdding(false);
              }}
            />
          </div>
        )}
        {!adding && tasks.length === 0 && !loading && <div className="ai-note">개인 할 일이 없어요.</div>}
        {tasks.map((t) => (
          <PersonalTodoRow key={t.personalTaskId} task={t} workspaceId={workspaceId} run={run} />
        ))}
      </div>
    </>
  );
}

function PersonalTodoRow({
  workspaceId,
  task,
  run,
}: {
  workspaceId: string;
  task: PersonalTaskResponse;
  run: (action: () => Promise<unknown>) => Promise<void>;
}) {
  const [title, setTitle] = useState(task.title);
  const done = task.status === 'DONE';

  const save = () => {
    if (!title.trim()) {
      setTitle(task.title);
      return;
    }
    if (title.trim() !== task.title) {
      void run(() => updatePersonalTask(workspaceId, task.personalTaskId, { title: title.trim(), dueDate: task.dueDate }));
    }
  };
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.nativeEvent.isComposing) (e.target as HTMLInputElement).blur();
  };

  return (
    <div className={`personal-todo${done ? ' done' : ''}`}>
      <button
        type="button"
        className={`ai-checkbox${done ? ' checked' : ''}`}
        aria-label={done ? '완료 취소' : '완료'}
        onClick={() =>
          run(() =>
            done
              ? reopenPersonalTask(workspaceId, task.personalTaskId)
              : completePersonalTask(workspaceId, task.personalTaskId),
          )
        }
      >
        {done && <Icon name="check" size={12} />}
      </button>
      <input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} onBlur={save} onKeyDown={onKeyDown} />
      {task.dueDate && <span className="due">{formatDate(task.dueDate)}</span>}
      <button
        type="button"
        className="delete"
        aria-label="삭제"
        onClick={() => run(() => deletePersonalTask(workspaceId, task.personalTaskId))}
      >
        <Icon name="trash" size={14} />
      </button>
    </div>
  );
}
