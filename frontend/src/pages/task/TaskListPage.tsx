import { type FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import {
  createTask,
  deleteTask,
  getMyTasks,
  getTask,
  getTasks,
  updateTask,
  type DeliverableInput,
  type TaskStatus,
  type TaskSummary,
} from './api';
import {
  getMembers,
  type MemberResponse,
} from '../workspace/api';
import PageHeader from '../../components/PageHeader';
import { BTN_AI, BTN_SUB, CARD, INPUT, MUTED } from '../../components/ui';
import NavIcon from '../../layouts/NavIcon';

export default function TaskListPage() {
  const { workspaceId } = useParams();

  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [members, setMembers] = useState<MemberResponse[]>([]);
  const [mineOnly, setMineOnly] = useState(false);

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

  // 상태
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [editLoading, setEditLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deletingTaskId, setDeletingTaskId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);

  /** Task 목록 조회 */
  const loadTasks = async () => {
    if (!workspaceId) {
      return;
    }

    try {
      setLoading(true);
      setError(null);

      const result = mineOnly
        ? await getMyTasks(workspaceId, page, pageSize)
        : await getTasks(workspaceId, page, pageSize);

      setTasks(result.items);
      setTotal(result.total);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : 'Task를 불러오지 못했습니다.',
      );
    } finally {
      setLoading(false);
    }
  };

  /** 워크스페이스 팀원 조회 */
  const loadMembers = async () => {
    if (!workspaceId) {
      return;
    }

    try {
      const result = await getMembers(workspaceId);
      setMembers(result);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : '팀원 목록을 불러오지 못했습니다.',
      );
    }
  };

  useEffect(() => {
    void loadTasks();
  }, [workspaceId, mineOnly, page]);

  useEffect(() => {
    void loadMembers();
  }, [workspaceId]);

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
      await loadTasks();
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : 'Task를 생성하지 못했습니다.',
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
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : 'Task 상세정보를 불러오지 못했습니다.',
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
          status: editStatus,
        },
      );

      handleEditCancel();
      await loadTasks();
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : 'Task를 수정하지 못했습니다.',
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

    const confirmed = window.confirm(
      '이 Task를 삭제하시겠습니까?',
    );

    if (!confirmed) {
      return;
    }

    try {
      setDeletingTaskId(taskId);
      setError(null);

      await deleteTask(workspaceId, taskId);

      if (editingTaskId === taskId) {
        handleEditCancel();
      }

      await loadTasks();
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : 'Task를 삭제하지 못했습니다.',
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

    return member?.name ?? '알 수 없음';
  };

  const totalPages = Math.max(
    1,
    Math.ceil(total / pageSize),
  );

  const [statusFilter, setStatusFilter] = useState<'ALL' | TaskStatus>('ALL');
  const [query, setQuery] = useState('');
  const navigate = useNavigate();

  const inProgress = tasks.filter((t) => t.status !== 'DONE').length;
  const done = tasks.filter((t) => t.status === 'DONE').length;
  const atRisk = tasks.filter((t) => t.status !== 'DONE' && t.riskLevel !== 'NORMAL').length;
  const q = query.trim().toLowerCase();
  const visible = tasks.filter(
    (t) => (statusFilter === 'ALL' || t.status === statusFilter) && (!q || t.title.toLowerCase().includes(q)),
  );

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="tasks" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="Task 관리"
        description="산출물 제출에 따라 진행률이 자동 계산되며, 작업을 클릭하면 협업 맥락으로 이동합니다."
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setShowCreate((v) => !v)}>
            {showCreate ? '닫기' : '+ 새 Task 추가'}
          </button>
        }
      />

      <div className="task-kpis">
        <div className="card task-kpi">
          <span>{mineOnly ? '내 Task' : '전체'}</span>
          <strong>{total}건</strong>
        </div>
        <div className="card task-kpi">
          <span>진행 중</span>
          <strong className="text-[var(--primary)]">{inProgress}건</strong>
        </div>
        <div className="card task-kpi">
          <span>완료</span>
          <strong className="text-[var(--success)]">{done}건</strong>
        </div>
        <div className="card task-kpi">
          <span>마감 주의</span>
          <strong className="text-[var(--warning)]">{atRisk}건</strong>
        </div>
      </div>

      {/* Task 생성 */}
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
            {creating ? '생성 중...' : 'Task 만들기'}
          </button>
        </form>
      )}

      {error && <div className="alert alert-error">{error}</div>}

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
                  onClick={() => setStatusFilter(value)}
                >
                  {label}
                </button>
              ))}
            </div>
            <div className="segmented">
              {[
                { mine: false, label: '모두의 Task' },
                { mine: true, label: '내 Task' },
              ].map((t) => (
                <button
                  key={t.label}
                  type="button"
                  className={mineOnly === t.mine ? 'active' : ''}
                  onClick={() => {
                    setMineOnly(t.mine);
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
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>

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
        ) : visible.length === 0 ? (
          <div className={`p-8 text-center text-sm ${MUTED}`}>
            {tasks.length === 0 ? '등록된 Task가 없습니다. "+ 새 Task 추가"로 만들어 보세요.' : '조건에 맞는 Task가 없습니다.'}
          </div>
        ) : (
          visible.map((task) =>
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
                <label className="block">
                  <span className={`mb-1 block text-xs font-bold ${MUTED}`}>상태</span>
                  <select
                    className={INPUT}
                    value={editStatus}
                    onChange={(e) => setEditStatus(e.target.value as TaskStatus)}
                  >
                    <option value="IN_PROGRESS">진행 중</option>
                    <option value="DONE">완료</option>
                  </select>
                </label>
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
          <button type="button" className={BTN_SUB} disabled={page === 0} onClick={() => setPage((current) => current - 1)}>
            이전
          </button>
          <span className="text-xs font-bold">
            {page + 1} / {totalPages}
          </span>
          <button
            type="button"
            className={BTN_SUB}
            disabled={(page + 1) * pageSize >= total}
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
