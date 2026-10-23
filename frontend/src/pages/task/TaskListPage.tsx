import { type FormEvent, useEffect, useState } from 'react';
import { useParams } from 'react-router';
import {
  createTask,
  deleteTask,
  getMyTasks,
  getTask,
  getTasks,
  updateTask,
  type TaskStatus,
  type TaskSummary,
} from './api';
import {
  getMembers,
  type MemberResponse,
} from '../workspace/api';
import PageHeader from '../../components/PageHeader';
import { BTN_AI, BTN_DANGER, BTN_SUB, CARD, INPUT, MUTED, SUB_CARD } from '../../components/ui';

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
        deliverables: [],
      });

      setTitle('');
      setDescription('');
      setAssigneeId('');
      setDueDate('');
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

  return (
    <section className="space-y-6">
      <PageHeader
        eyebrow="TASK BOARD"
        title="작업 (Task)"
        description="담당자 · 마감일 · 필요 산출물을 정하고 진행 상황과 마감 위험도를 확인합니다."
        actions={
          <button type="button" className={BTN_AI} onClick={() => setShowCreate((v) => !v)}>
            {showCreate ? '닫기' : '+ 새 Task'}
          </button>
        }
      />

      {/* 전체 / 내 Task */}
      <div className={`${SUB_CARD} inline-flex gap-1 p-1`}>
        {[
          { mine: false, label: '👥 전체 Task' },
          { mine: true, label: '🙋 내 Task' },
        ].map((t) => (
          <button
            key={t.label}
            type="button"
            onClick={() => {
              setMineOnly(t.mine);
              setPage(0);
            }}
            className={`cursor-pointer rounded-xl border-0 px-4 py-1.5 text-xs font-bold transition ${
              mineOnly === t.mine ? 'bg-indigo-600 text-white' : `bg-transparent ${MUTED}`
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Task 생성 */}
      {showCreate && (
        <form onSubmit={handleCreate} className={`${CARD} space-y-4 p-7`}>
          <h2 className="!text-lg">새 Task</h2>
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
          <button type="submit" className={BTN_AI} disabled={creating}>
            {creating ? '생성 중...' : 'Task 만들기'}
          </button>
        </form>
      )}

      {error && <div className="alert alert-error">{error}</div>}

      {loading ? (
        <p className={`text-sm ${MUTED}`}>불러오는 중...</p>
      ) : tasks.length === 0 ? (
        <div className={`${CARD} p-10 text-center`}>
          <p className={`m-0 text-sm ${MUTED}`}>등록된 Task가 없습니다. "+ 새 Task"로 만들어 보세요.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {tasks.map((task) =>
            editingTaskId === task.taskId ? (
              /* 수정 폼 */
              <form key={task.taskId} onSubmit={handleEditSubmit} className={`${CARD} space-y-3 p-6`}>
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
              /* Task 카드 */
              <div key={task.taskId} className={`${CARD} flex flex-col justify-between gap-4 p-6`}>
                <div>
                  <div className="mb-3 flex items-center gap-2">
                    <span className={`rounded-full px-2.5 py-0.5 text-[11px] font-bold ${RISK[task.riskLevel]?.className ?? ''}`}>
                      {RISK[task.riskLevel]?.text ?? task.riskLevel}
                    </span>
                    {task.status === 'DONE' && (
                      <span className="rounded-full bg-emerald-500/15 px-2.5 py-0.5 text-[11px] font-bold text-emerald-600 dark:text-emerald-400">
                        ✓ 완료
                      </span>
                    )}
                    <span className="flex-1" />
                    <span className="text-xs font-bold text-indigo-500">{task.progress}%</span>
                  </div>
                  <h3 className="m-0 text-base leading-snug font-bold">{task.title}</h3>
                  <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-200/70 dark:bg-white/10">
                    <div
                      className="h-full rounded-full bg-[linear-gradient(90deg,#4F46E5,#7C3AED)]"
                      style={{ width: `${Math.min(100, Math.max(0, task.progress))}%` }}
                    />
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <span className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full bg-indigo-600 text-[10px] font-bold text-white">
                    {getAssigneeName(task.assigneeId).slice(0, 2)}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="m-0 truncate text-xs font-bold">{getAssigneeName(task.assigneeId)}</p>
                    <p className={`m-0 text-[11px] ${MUTED}`}>마감 {task.dueDate ?? '미정'}</p>
                  </div>
                </div>

                <div className="flex gap-2 border-0 border-t border-solid border-slate-200/60 pt-3 dark:border-white/10">
                  <button
                    type="button"
                    className={BTN_SUB}
                    disabled={editLoading}
                    onClick={() => void handleEditStart(task.taskId)}
                  >
                    수정
                  </button>
                  <button
                    type="button"
                    className={BTN_DANGER}
                    disabled={deletingTaskId === task.taskId}
                    onClick={() => void handleDelete(task.taskId)}
                  >
                    {deletingTaskId === task.taskId ? '삭제 중...' : '삭제'}
                  </button>
                </div>
              </div>
            ),
          )}
        </div>
      )}

      {/* 페이지네이션 */}
      {!loading && total > 0 && (
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

const RISK: Record<string, { text: string; className: string }> = {
  NORMAL: { text: '● 정상', className: 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400' },
  WARNING: { text: '● 주의', className: 'bg-amber-500/20 text-amber-600 dark:text-amber-400' },
  DANGER: { text: '● 위험', className: 'bg-rose-500/15 text-rose-600 dark:text-rose-400' },
};

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
