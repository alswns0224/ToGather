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

export default function TaskListPage() {
  const { workspaceId } = useParams();

  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [members, setMembers] = useState<MemberResponse[]>([]);
  const [mineOnly, setMineOnly] = useState(false);

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

  /** Task 목록 조회 */
  const loadTasks = async () => {
    if (!workspaceId) {
      return;
    }

    try {
      setLoading(true);
      setError(null);

      const result = mineOnly
        ? await getMyTasks(workspaceId)
        : await getTasks(workspaceId);

      setTasks(result);
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
  }, [workspaceId, mineOnly]);

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

  return (
    <section>
      <h1>Task</h1>

      {/* 전체 / 내 Task */}
      <div>
        <button
          type="button"
          onClick={() => setMineOnly(false)}
          disabled={!mineOnly}
        >
          전체 Task
        </button>

        <button
          type="button"
          onClick={() => setMineOnly(true)}
          disabled={mineOnly}
        >
          내 Task
        </button>
      </div>

      <hr />

      {/* Task 생성 */}
      <h2>새 Task</h2>

      <form onSubmit={handleCreate}>
        <div>
          <label>
            제목 *
            <input
              type="text"
              value={title}
              onChange={(e) =>
                setTitle(e.target.value)
              }
              required
            />
          </label>
        </div>

        <div>
          <label>
            설명
            <textarea
              value={description}
              onChange={(e) =>
                setDescription(e.target.value)
              }
            />
          </label>
        </div>

        <div>
          <label>
            담당자
            <select
              value={assigneeId}
              onChange={(e) =>
                setAssigneeId(e.target.value)
              }
            >
              <option value="">미지정</option>

              {members.map((member) => (
                <option
                  key={member.userId}
                  value={member.userId}
                >
                  {member.name} ({member.email})
                </option>
              ))}
            </select>
          </label>
        </div>

        <div>
          <label>
            마감일
            <input
              type="date"
              value={dueDate}
              onChange={(e) =>
                setDueDate(e.target.value)
              }
            />
          </label>
        </div>

        <button
          type="submit"
          disabled={creating}
        >
          {creating
            ? '생성 중...'
            : 'Task 만들기'}
        </button>
      </form>

      <hr />

      {/* Task 목록 */}
      <h2>
        {mineOnly ? '내 Task' : '전체 Task'}
      </h2>

      {error && <p>{error}</p>}

      {loading ? (
        <p>불러오는 중...</p>
      ) : tasks.length === 0 ? (
        <p>등록된 Task가 없습니다.</p>
      ) : (
        <ul>
          {tasks.map((task) => (
            <li key={task.taskId}>
              {editingTaskId === task.taskId ? (
                /* 수정 폼 */
                <form onSubmit={handleEditSubmit}>
                  <div>
                    <label>
                      제목 *
                      <input
                        type="text"
                        value={editTitle}
                        onChange={(e) =>
                          setEditTitle(e.target.value)
                        }
                        required
                      />
                    </label>
                  </div>

                  <div>
                    <label>
                      설명
                      <textarea
                        value={editDescription}
                        onChange={(e) =>
                          setEditDescription(
                            e.target.value,
                          )
                        }
                      />
                    </label>
                  </div>

                  <div>
                    <label>
                      담당자
                      <select
                        value={editAssigneeId}
                        onChange={(e) =>
                          setEditAssigneeId(
                            e.target.value,
                          )
                        }
                      >
                        <option value="">
                          미지정
                        </option>

                        {members.map((member) => (
                          <option
                            key={member.userId}
                            value={member.userId}
                          >
                            {member.name} (
                            {member.email})
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>

                  <div>
                    <label>
                      마감일
                      <input
                        type="date"
                        value={editDueDate}
                        onChange={(e) =>
                          setEditDueDate(
                            e.target.value,
                          )
                        }
                      />
                    </label>
                  </div>

                  <div>
                    <label>
                      상태
                      <select
                        value={editStatus}
                        onChange={(e) =>
                          setEditStatus(
                            e.target.value as TaskStatus,
                          )
                        }
                      >
                        <option value="IN_PROGRESS">
                          진행 중
                        </option>
                        <option value="DONE">
                          완료
                        </option>
                      </select>
                    </label>
                  </div>

                  <button
                    type="submit"
                    disabled={saving}
                  >
                    {saving
                      ? '저장 중...'
                      : '저장'}
                  </button>

                  <button
                    type="button"
                    onClick={handleEditCancel}
                    disabled={saving}
                  >
                    취소
                  </button>
                </form>
              ) : (
                /* 일반 목록 */
                <>
                  <strong>{task.title}</strong>

                  <div>
                    담당자:{' '}
                    {getAssigneeName(
                      task.assigneeId,
                    )}
                  </div>

                  <div>
                    상태:{' '}
                    {task.status === 'DONE'
                      ? '완료'
                      : '진행 중'}
                  </div>

                  <div>
                    마감일:{' '}
                    {task.dueDate ?? '미정'}
                  </div>

                  <div>
                    진행률: {task.progress}%
                  </div>

                  <div>
                    위험도: {task.riskLevel}
                  </div>

                  <button
                    type="button"
                    disabled={editLoading}
                    onClick={() =>
                      void handleEditStart(
                        task.taskId,
                      )
                    }
                  >
                    수정
                  </button>

                  <button
                    type="button"
                    disabled={
                      deletingTaskId === task.taskId
                    }
                    onClick={() =>
                      void handleDelete(
                        task.taskId,
                      )
                    }
                  >
                    {deletingTaskId === task.taskId
                      ? '삭제 중...'
                      : '삭제'}
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
