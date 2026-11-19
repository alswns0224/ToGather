import { type FormEvent, useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  createTask,
  deleteTask,
  getTaskProgress,
  getTask,
  getTaskDeletionBlockers,
  getTasks,
  updateTask,
  type DeliverableInput,
  type TaskStatus,
  type TaskSummary,
  type TaskContext,
  type TaskFilters,
  type TaskProgressResponse,
} from './api';
import { ApiError } from '../../api/client';
import {
  getMembers,
  type MemberResponse,
} from '../workspace/api';
import PageHeader from '../../components/PageHeader';
import { BTN_AI, BTN_SUB, CARD, INPUT, MUTED } from '../../components/ui';
import NavIcon from '../../layouts/NavIcon';
import TaskProgressPanel from './TaskProgressPanel';

export default function TaskListPage() {
  const { workspaceId } = useParams();
  return workspaceId ? <TaskList key={workspaceId} workspaceId={workspaceId} /> : null;
}

function TaskList({ workspaceId }: { workspaceId: string }) {
  const [params] = useSearchParams();
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [members, setMembers] = useState<MemberResponse[]>([]);
  const [mineOnly, setMineOnly] = useState(false);
  const [assigneeFilter, setAssigneeFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState<'ALL' | TaskStatus>(() => {
    const value = params.get('status');
    return value === 'DONE' || value === 'IN_PROGRESS' ? value : 'ALL';
  });
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');
  const [dueBefore, setDueBefore] = useState(() => /^\d{4}-\d{2}-\d{2}$/.test(params.get('dueBefore') ?? '') ? params.get('dueBefore')! : '');
  const [sort, setSort] = useState<'RECENT' | 'DUE_DATE'>(params.get('sort') === 'DUE_DATE' ? 'DUE_DATE' : 'RECENT');
  const [revision, setRevision] = useState(0);
  const [progress, setProgress] = useState<TaskProgressResponse | null>(null);
  const [progressLoading, setProgressLoading] = useState(true);
  const [progressError, setProgressError] = useState<string | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [membersError, setMembersError] = useState<string | null>(null);

  // 페이지네이션
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const pageSize = 20;

  // Task 생성 폼
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [assigneeId, setAssigneeId] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [deliverables, setDeliverables] = useState<DeliverableInput[]>([]);

  // Task 수정 폼
  const [editingTaskId, setEditingTaskId] = useState<number | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editAssigneeId, setEditAssigneeId] = useState('');
  const [editDueDate, setEditDueDate] = useState('');
  const [editStatus, setEditStatus] = useState<TaskStatus>('IN_PROGRESS');
  const [editStatusManaged, setEditStatusManaged] = useState(false);
  const [blockingMeetings, setBlockingMeetings] = useState<TaskContext['meetings']>([]);

  // 상태
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [editLoading, setEditLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deletingTaskId, setDeletingTaskId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);

  const filters = (): TaskFilters => ({
    mine: mineOnly,
    ...(assigneeFilter === 'UNASSIGNED' ? { unassigned: true }
      : assigneeFilter === 'ALL' ? {} : { assigneeId: Number(assigneeFilter) }),
    ...(statusFilter === 'ALL' ? {} : { status: statusFilter }),
    query,
    ...(dueBefore ? { dueBefore } : {}),
    sort,
  });

  useEffect(() => {
    if (queryInput.trim() === query) return;
    const timer = window.setTimeout(() => { setPage(0); setQuery(queryInput.trim()); }, 250);
    return () => window.clearTimeout(timer);
  }, [queryInput, query]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setListError(null);
    getTasks(workspaceId, page, pageSize, filters())
      .then((result) => {
        if (!active) return;
        const lastPage = Math.max(0, Math.ceil(result.total / pageSize) - 1);
        if (page > lastPage) { setPage(lastPage); return; }
        setTasks(result.items);
        setTotal(result.total);
      })
      .catch((reason: unknown) => { if (active) setListError(reason instanceof Error ? reason.message : '팀 작업을 불러오지 못했습니다.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [workspaceId, mineOnly, assigneeFilter, statusFilter, query, dueBefore, sort, page, revision]);

  useEffect(() => {
    let active = true;
    setProgress(null);
    setProgressLoading(true);
    setProgressError(null);
    getTaskProgress(workspaceId, filters())
      .then((result) => { if (active) setProgress(result); })
      .catch((reason: unknown) => { if (active) setProgressError(reason instanceof Error ? reason.message : '진행 현황을 불러오지 못했습니다.'); })
      .finally(() => { if (active) setProgressLoading(false); });
    return () => { active = false; };
  }, [workspaceId, mineOnly, assigneeFilter, statusFilter, query, dueBefore, sort, revision]);

  useEffect(() => {
    let active = true;
    setMembersError(null);
    getMembers(workspaceId)
      .then((result) => { if (active) setMembers(result); })
      .catch((reason: unknown) => { if (active) setMembersError(reason instanceof Error ? reason.message : '팀원 목록을 불러오지 못했습니다.'); });
    return () => { active = false; };
  }, [workspaceId, revision]);

  /** Task 생성 */
  const handleCreate = async (
    event: FormEvent<HTMLFormElement>,
  ) => {
    event.preventDefault();

    if (!workspaceId || !title.trim()) {
      return;
    }

    try {
      setCreating(true);
      setError(null);

      await createTask(workspaceId, {
        title: title.trim(),
        description: description.trim() || undefined,
        assigneeId: assigneeId
          ? Number(assigneeId)
          : null,
        dueDate: dueDate || null,
        status: 'IN_PROGRESS',
        deliverables: deliverables.map(({ name, ext }) => ({ name: name.trim(), ext: ext.trim() })),
      });

      setTitle('');
      setDescription('');
      setAssigneeId('');
      setDueDate('');
      setDeliverables([]);
      setShowCreate(false);

      setPage(0);
      setRevision((value) => value + 1);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : '팀 작업을 생성하지 못했습니다.',
      );
    } finally {
      setCreating(false);
    }
  };

  /** 수정 시작 - 상세 조회 후 수정 폼에 기존 값 채움 */
  const handleEditStart = async (taskId: number) => {
    if (!workspaceId) {
      return;
    }

    try {
      setEditLoading(true);
      setError(null);

      const task = await getTask(workspaceId, taskId);

      setEditingTaskId(task.taskId);
      setEditTitle(task.title);
      setEditDescription(task.description ?? '');
      setEditAssigneeId(
        task.assigneeId === null
          ? ''
          : String(task.assigneeId),
      );
      setEditDueDate(task.dueDate ?? '');
      setEditStatus(task.status);
      setEditStatusManaged(task.statusManagedByDeliverables);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : '팀 작업 상세정보를 불러오지 못했습니다.',
      );
    } finally {
      setEditLoading(false);
    }
  };

  /** 수정 취소 */
  const handleEditCancel = () => {
    setEditingTaskId(null);
    setEditTitle('');
    setEditDescription('');
    setEditAssigneeId('');
    setEditDueDate('');
    setEditStatus('IN_PROGRESS');
    setEditStatusManaged(false);
  };

  /** Task 수정 저장 */
  const handleEditSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ) => {
    event.preventDefault();

    if (
      !workspaceId ||
      editingTaskId === null ||
      !editTitle.trim()
    ) {
      return;
    }

    try {
      setSaving(true);
      setError(null);

      await updateTask(
        workspaceId,
        editingTaskId,
        {
          title: editTitle.trim(),
          description:
            editDescription.trim() || undefined,
          assigneeId: editAssigneeId
            ? Number(editAssigneeId)
            : null,
          dueDate: editDueDate || null,
          ...(editStatusManaged ? {} : { status: editStatus }),
        },
      );

      handleEditCancel();
      setRevision((value) => value + 1);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : '팀 작업을 수정하지 못했습니다.',
      );
    } finally {
      setSaving(false);
    }
  };

  /** Task 삭제 */
  const handleDelete = async (taskId: number) => {
    if (!workspaceId) {
      return;
    }

    try {
      setDeletingTaskId(taskId);
      setError(null);
      setBlockingMeetings([]);

      const blockers = await getTaskDeletionBlockers(workspaceId, taskId);
      if (blockers.length > 0) {
        setBlockingMeetings(blockers);
        return;
      }
      if (!window.confirm('이 팀 작업과 연결된 자료·산출물 등록 정보를 삭제하시겠습니까? 원본 파일은 유지됩니다.')) {
        return;
      }

      await deleteTask(workspaceId, taskId);

      if (editingTaskId === taskId) {
        handleEditCancel();
      }

      setRevision((value) => value + 1);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'TASK_IN_ACTIVE_MEETING') {
        try {
          setBlockingMeetings(await getTaskDeletionBlockers(workspaceId, taskId));
        } catch {
          // 목록을 다시 가져오지 못해도 서버가 반환한 삭제 거절 사유는 표시한다.
        }
      }
      setError(
        e instanceof Error
          ? e.message
          : '팀 작업을 삭제하지 못했습니다.',
      );
    } finally {
      setDeletingTaskId(null);
    }
  };

  /** 담당자 ID → 이름 */
  const getAssigneeName = (id: number | null) => {
    if (id === null) {
      return '미지정';
    }

    const member = members.find(
      (item) => item.userId === id,
    );

    return member?.name ?? (membersError ? '이름 조회 실패' : '이전 팀원');
  };

  const totalPages = Math.max(
    1,
    Math.ceil(total / pageSize),
  );

  const navigate = useNavigate();
  const summary = !progressLoading ? progress?.overall : null;
  const filtered = mineOnly || assigneeFilter !== 'ALL' || statusFilter !== 'ALL' || !!query || !!dueBefore;
  const previousAssignees = [...new Set([
    ...(progress?.byAssignee.map((item) => item.assigneeId).filter((id): id is number => id !== null) ?? []),
    ...(assigneeFilter !== 'ALL' && assigneeFilter !== 'UNASSIGNED' ? [Number(assigneeFilter)] : []),
  ])].filter((id) => !members.some((member) => member.userId === id));
  const selectAssignee = (id: number | null) => {
    setMineOnly(false);
    setAssigneeFilter(id === null ? 'UNASSIGNED' : String(id));
    setPage(0);
  };

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="tasks" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="팀 작업"
        description="산출물 제출에 따라 진행률이 자동 계산되며, 작업을 클릭하면 협업 맥락으로 이동합니다."
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setShowCreate((v) => !v)}>
            {showCreate ? '닫기' : '+ 새 팀 작업'}
          </button>
        }
      />

      <div className="task-kpis">
        <div className="card task-kpi">
          <span>{filtered ? '조회 조건 전체' : '전체 작업'}</span>
          <strong>{summary?.totalCount ?? '—'}건</strong>
        </div>
        <div className="card task-kpi">
          <span>진행 중</span>
          <strong className="text-[var(--primary)]">{summary?.inProgressCount ?? '—'}건</strong>
        </div>
        <div className="card task-kpi">
          <span>완료</span>
          <strong className="text-[var(--success)]">{summary?.doneCount ?? '—'}건</strong>
        </div>
        <div className="card task-kpi">
          <span>마감 주의</span>
          <strong className="text-[var(--warning)]">{summary ? summary.warningCount + summary.dangerCount : '—'}건</strong>
        </div>
      </div>

      <TaskProgressPanel data={progress} loading={progressLoading} error={progressError} filtered={filtered}
        memberName={getAssigneeName} onSelectAssignee={selectAssignee} onRetry={() => setRevision((value) => value + 1)} />

      {/* 팀 작업 생성 */}
      {showCreate && (
        <form onSubmit={handleCreate} className={`${CARD} space-y-4 p-5`}>
          <strong className="text-[15px]">새 Task</strong>
          <TaskFields
            title={title}
            setTitle={setTitle}
            description={description}
            setDescription={setDescription}
            assigneeId={assigneeId}
            setAssigneeId={setAssigneeId}
            dueDate={dueDate}
            setDueDate={setDueDate}
            members={members}
          />
          <fieldset className="space-y-2">
            <legend className={`text-xs font-bold ${MUTED}`}>필수 산출물</legend>
            {deliverables.map((deliverable, index) => (
              <div key={index} className="grid grid-cols-[minmax(0,1fr)_minmax(0,8rem)_auto] items-end gap-2">
                <label className="block">
                  <span className={`mb-1 block text-xs ${MUTED}`}>산출물 이름</span>
                  <input
                    className={INPUT}
                    value={deliverable.name}
                    maxLength={200}
                    required
                    onChange={(event) => {
                      const name = event.target.value;
                      setDeliverables(items => items.map((item, position) => position === index ? { ...item, name } : item));
                    }}
                  />
                </label>
                <label className="block">
                  <span className={`mb-1 block text-xs ${MUTED}`}>허용 확장자</span>
                  <input
                    className={INPUT}
                    value={deliverable.ext}
                    placeholder="예: pdf"
                    pattern="[.]?[a-zA-Z0-9]+"
                    maxLength={20}
                    onChange={(event) => {
                      const ext = event.target.value;
                      setDeliverables(items => items.map((item, position) => position === index ? { ...item, ext } : item));
                    }}
                  />
                </label>
                <button
                  type="button"
                  className={BTN_SUB}
                  onClick={() => setDeliverables(items => items.filter((_, position) => position !== index))}
                >
                  제거
                </button>
              </div>
            ))}
            <button type="button" className={BTN_SUB} onClick={() => setDeliverables(items => [...items, { name: '', ext: '' }])}>
              + 산출물 추가
            </button>
          </fieldset>
          <button type="submit" className={BTN_AI} disabled={creating}>
            {creating ? '생성 중...' : '팀 작업 만들기'}
          </button>
        </form>
      )}

      {error && <div className="alert alert-error">{error}</div>}
      {blockingMeetings.length > 0 && (
        <div className="alert alert-error !flex-col !items-start" role="alert">
          <strong>회의 안건으로 사용 중인 팀 작업은 삭제할 수 없어요.</strong>
          <p className="my-1 text-sm">아래 회의에서 안건을 해제한 뒤 다시 삭제해 주세요.</p>
          <ul className="my-1 pl-5 text-sm">
            {blockingMeetings.map(meeting => (
              <li key={meeting.meetingId}>
                <Link to={`/workspaces/${workspaceId}/meetings/${meeting.meetingId}`}>{meeting.title}</Link>
                {' · '}{meeting.status === 'SCHEDULING' ? '일정 조율 중' : '확정된 회의'}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="card overflow-hidden !p-0">
        <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
          <div className="flex flex-wrap gap-2">
            <div className="segmented">
              {(
                [
                  ['ALL', '전체'],
                  ['IN_PROGRESS', '진행 중'],
                  ['DONE', '완료'],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  className={statusFilter === value ? 'active' : ''}
                  onClick={() => { setStatusFilter(value); setPage(0); }}
                >
                  {label}
                </button>
              ))}
            </div>
            <div className="segmented">
              {[
                { mine: false, label: '모두의 작업' },
                { mine: true, label: '내 담당' },
              ].map((t) => (
                <button
                  key={t.label}
                  type="button"
                  className={mineOnly === t.mine ? 'active' : ''}
                  onClick={() => {
                    setMineOnly(t.mine);
                    setAssigneeFilter('ALL');
                    setPage(0);
                  }}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>
          <input
            className="input !w-[300px] max-w-full"
            placeholder="작업명 검색..."
            value={queryInput}
            maxLength={200}
            aria-label="작업명 검색"
            onChange={(e) => setQueryInput(e.target.value)}
          />
        </div>

        <div className="flex flex-wrap items-center gap-3 border-t border-[var(--border-soft)] px-5 py-3 text-sm">
          <label className="flex items-center gap-2">담당자
            <select className="input !w-auto" aria-label="담당자 필터" value={assigneeFilter} disabled={mineOnly}
              onChange={(event) => { setAssigneeFilter(event.target.value); setPage(0); }}>
              <option value="ALL">전체 담당자</option><option value="UNASSIGNED">미지정</option>
              {members.map((member) => <option key={member.userId} value={member.userId}>{member.name}</option>)}
              {previousAssignees.map((id) => <option key={id} value={id}>이전 팀원</option>)}
            </select>
          </label>
          <label className="flex items-center gap-2">정렬
            <select className="input !w-auto" aria-label="작업 정렬" value={sort}
              onChange={(event) => { setSort(event.target.value as 'RECENT' | 'DUE_DATE'); setPage(0); }}>
              <option value="RECENT">최근 생성순</option><option value="DUE_DATE">마감일순</option>
            </select>
          </label>
          {dueBefore && <span className="badge">마감 {dueBefore} 이전</span>}
          {filtered && <button type="button" className="link-button" onClick={() => {
            setMineOnly(false); setAssigneeFilter('ALL'); setStatusFilter('ALL');
            setQueryInput(''); setQuery(''); setDueBefore(''); setPage(0);
          }}>조건 초기화</button>}
        </div>
        {listError && <div role="alert" className="alert alert-error mx-5 mb-3"><span>{listError}</span>
          <button type="button" className="btn btn-small" onClick={() => setRevision((value) => value + 1)}>다시 시도</button>
        </div>}
        {membersError && <div role="alert" className="alert alert-error mx-5 mb-3"><span>{membersError}</span>
          <button type="button" className="btn btn-small" onClick={() => setRevision((value) => value + 1)}>팀원 다시 조회</button>
        </div>}
        <div className="task-row task-head">
          <span>작업명</span>
          <span>담당자</span>
          <span>진행률</span>
          <span>마감일</span>
          <span>상태</span>
          <span />
        </div>

        {loading ? (
          <div className={`p-8 text-center text-sm ${MUTED}`}>불러오는 중...</div>
        ) : listError ? null : tasks.length === 0 ? (
          <div className={`p-8 text-center text-sm ${MUTED}`}>
            {filtered ? '조건에 맞는 팀 작업이 없습니다.' : '등록된 팀 작업이 없습니다. 새 팀 작업을 만들어 보세요.'}
          </div>
        ) : (
          tasks.map((task) =>
            editingTaskId === task.taskId ? (
              /* 수정 폼 */
              <form key={task.taskId} onSubmit={handleEditSubmit} className="space-y-3 border-b border-[var(--border-soft)] bg-[var(--surface-2)] p-5">
                <TaskFields
                  title={editTitle}
                  setTitle={setEditTitle}
                  description={editDescription}
                  setDescription={setEditDescription}
                  assigneeId={editAssigneeId}
                  setAssigneeId={setEditAssigneeId}
                  dueDate={editDueDate}
                  setDueDate={setEditDueDate}
                  members={members}
                />
                {editStatusManaged ? (
                  <div className="text-sm text-[var(--muted)]">
                    상태: {editStatus === 'DONE' ? '완료' : '진행 중'} · 산출물 제출 여부에 따라 자동으로 변경돼요.
                    <Link className="ml-2" to={`/workspaces/${workspaceId}/tasks/${task.taskId}`}>산출물 관리</Link>
                  </div>
                ) : <label className="block">
                  <span className={`mb-1 block text-xs font-bold ${MUTED}`}>상태</span>
                  <select
                    className={INPUT}
                    value={editStatus}
                    onChange={(e) => setEditStatus(e.target.value as TaskStatus)}
                  >
                    <option value="IN_PROGRESS">진행 중</option>
                    <option value="DONE">완료</option>
                  </select>
                </label>}
                <div className="flex gap-2">
                  <button type="submit" className={BTN_AI} disabled={saving}>
                    {saving ? '저장 중...' : '저장'}
                  </button>
                  <button type="button" className={BTN_SUB} onClick={handleEditCancel} disabled={saving}>
                    취소
                  </button>
                </div>
              </form>
            ) : (
              <div
                key={task.taskId}
                className="task-row task-item"
                onClick={() => navigate(`/workspaces/${workspaceId}/tasks/${task.taskId}`)}
              >
                <div className="min-w-0">
                  <div className="truncate text-base font-bold">{task.title}</div>
                  <div className="text-xs text-[var(--muted)]">협업 맥락 · 자료 보기 →</div>
                </div>
                <span className="text-sm">{getAssigneeName(task.assigneeId)}</span>
                <div className="flex items-center gap-2">
                  <div className="h-2 flex-1 overflow-hidden rounded-full bg-[var(--surface-2)]">
                    <div
                      className="h-full rounded-full bg-[image:var(--brand-grad)]"
                      style={{ width: `${Math.min(100, Math.max(0, task.progress))}%` }}
                    />
                  </div>
                  <span className="w-10 text-right text-sm font-bold">{task.progress}%</span>
                </div>
                <span className="text-sm">{task.dueDate ?? '미정'}</span>
                <span className="flex flex-wrap gap-1">
                  <span className={`badge ${task.status === 'DONE' ? 'badge-success' : 'badge-primary'}`}>
                    {task.status === 'DONE' ? '완료' : '진행 중'}
                  </span>
                  {task.status !== 'DONE' && task.riskLevel !== 'NORMAL' && (
                    <span className={`badge ${task.riskLevel === 'DANGER' ? 'badge-danger' : 'badge-warning'}`}>
                      {task.riskLevel === 'DANGER' ? '위험' : '주의'}
                    </span>
                  )}
                </span>
                <span className="flex justify-end gap-1" onClick={(e) => e.stopPropagation()}>
                  <button
                    type="button"
                    className="btn btn-small"
                    disabled={editLoading}
                    onClick={() => void handleEditStart(task.taskId)}
                  >
                    수정
                  </button>
                  <button
                    type="button"
                    className="btn btn-small btn-danger"
                    disabled={deletingTaskId === task.taskId}
                    onClick={() => void handleDelete(task.taskId)}
                  >
                    {deletingTaskId === task.taskId ? '삭제 중' : '삭제'}
                  </button>
                </span>
              </div>
            ),
          )
        )}
      </div>

      {/* 페이지네이션 */}
      {!loading && total > pageSize && (
        <div className="flex items-center justify-center gap-3">
          <button type="button" className={BTN_SUB} disabled={loading || page === 0} onClick={() => setPage((current) => current - 1)}>
            이전
          </button>
          <span className="text-xs font-bold">
            {page + 1} / {totalPages}
          </span>
          <button
            type="button"
            className={BTN_SUB}
            disabled={loading || (page + 1) * pageSize >= total}
            onClick={() => setPage((current) => current + 1)}
          >
            다음
          </button>
          <span className={`text-xs ${MUTED}`}>총 {total}개</span>
        </div>
      )}
    </section>
  );
}

/** 생성·수정 폼에서 함께 쓰는 입력칸 */
function TaskFields({
  title,
  setTitle,
  description,
  setDescription,
  assigneeId,
  setAssigneeId,
  dueDate,
  setDueDate,
  members,
}: {
  title: string;
  setTitle: (v: string) => void;
  description: string;
  setDescription: (v: string) => void;
  assigneeId: string;
  setAssigneeId: (v: string) => void;
  dueDate: string;
  setDueDate: (v: string) => void;
  members: MemberResponse[];
}) {
  return (
    <>
      <label className="block">
        <span className={`mb-1 block text-xs font-bold ${MUTED}`}>제목 *</span>
        <input className={INPUT} type="text" value={title} onChange={(e) => setTitle(e.target.value)} required />
      </label>
      <label className="block">
        <span className={`mb-1 block text-xs font-bold ${MUTED}`}>설명</span>
        <textarea
          className={`${INPUT} min-h-20 resize-y`}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </label>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="block">
          <span className={`mb-1 block text-xs font-bold ${MUTED}`}>담당자</span>
          <select className={INPUT} value={assigneeId} onChange={(e) => setAssigneeId(e.target.value)}>
            <option value="">미지정</option>
            {members.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.name} ({member.email})
              </option>
            ))}
          </select>
        </label>
        <label className="block">
          <span className={`mb-1 block text-xs font-bold ${MUTED}`}>마감일</span>
          <input className={INPUT} type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
        </label>
      </div>
    </>
  );
}
