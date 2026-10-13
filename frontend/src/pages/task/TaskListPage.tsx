import { FormEvent, useEffect, useState } from 'react';
import { useParams } from 'react-router';
import {
  createTask,
  getMyTasks,
  getTasks,
  TaskSummary,
} from './api';

export default function TaskListPage() {
  const { workspaceId } = useParams();

  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [mineOnly, setMineOnly] = useState(false);

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [dueDate, setDueDate] = useState('');

  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
      setError(e instanceof Error ? e.message : 'Task를 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadTasks();
  }, [workspaceId, mineOnly]);

  const handleCreate = async (event: FormEvent<HTMLFormElement>) => {
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
        dueDate: dueDate || null,
        status: 'IN_PROGRESS',
        deliverables: [],
      });

      setTitle('');
      setDescription('');
      setDueDate('');

      await loadTasks();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Task를 생성하지 못했습니다.');
    } finally {
      setCreating(false);
    }
  };

  return (
    <section>
      <h1>Task</h1>

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

      <h2>새 Task</h2>

      <form onSubmit={handleCreate}>
        <div>
          <label>
            제목 *
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              required
            />
          </label>
        </div>

        <div>
          <label>
            설명
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </label>
        </div>

        <div>
          <label>
            마감일
            <input
              type="date"
              value={dueDate}
              onChange={(e) => setDueDate(e.target.value)}
            />
          </label>
        </div>

        <button type="submit" disabled={creating}>
          {creating ? '생성 중...' : 'Task 만들기'}
        </button>
      </form>

      <hr />

      <h2>{mineOnly ? '내 Task' : '전체 Task'}</h2>

      {error && <p>{error}</p>}

      {loading ? (
        <p>불러오는 중...</p>
      ) : tasks.length === 0 ? (
        <p>등록된 Task가 없습니다.</p>
      ) : (
        <ul>
          {tasks.map((task) => (
            <li key={task.taskId}>
              <strong>{task.title}</strong>

              <div>
                상태: {task.status === 'DONE' ? '완료' : '진행 중'}
              </div>

              <div>
                마감일: {task.dueDate ?? '미정'}
              </div>

              <div>
                진행률: {task.progress}%
              </div>

              <div>
                위험도: {task.riskLevel}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
