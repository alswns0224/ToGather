// UI-04·11 Slack 메시지와 연결된 Task (담당: B·C)
import { useState } from 'react';
import { Link } from 'react-router';
import { ErrorBox, Loading } from '../../../components/Status';
import { useAsync } from '../../../hooks/useAsync';
import { getTasksByContent } from '../../task/api';

export default function LinkedMessageTasks({ workspaceId, contentRefId, initiallyOpen = false }: {
  workspaceId: string;
  contentRefId: number | null;
  initiallyOpen?: boolean;
}) {
  const [open, setOpen] = useState(initiallyOpen);

  if (contentRefId === null) {
    return <p className="mb-0 mt-2 text-xs text-[var(--muted)]">연결된 Task는 메시지 동기화 후 확인할 수 있어요.</p>;
  }
  return (
    <div className="mt-2" onClick={(event) => event.stopPropagation()}>
      <details open={open} onToggle={(event) => setOpen(event.currentTarget.open)}>
        <summary className="cursor-pointer text-xs font-semibold text-[var(--primary)]">연결된 Task</summary>
        {open && <MessageTaskList key={`${workspaceId}/${contentRefId}`} workspaceId={workspaceId} contentRefId={contentRefId} />}
      </details>
    </div>
  );
}

function MessageTaskList({ workspaceId, contentRefId }: { workspaceId: string; contentRefId: number }) {
  const tasks = useAsync(() => getTasksByContent(workspaceId, contentRefId), [workspaceId, contentRefId]);
  return (
    <div className="mt-2 flex min-w-0 flex-col gap-2 rounded-lg border border-[var(--border-soft)] bg-[var(--surface)] p-3">
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="font-semibold">이 메시지에 연결된 작업 {tasks.data && `(${tasks.data.length})`}</span>
        <button type="button" className="link-button" disabled={tasks.loading} onClick={tasks.reload}>새로고침</button>
      </div>
      {tasks.loading && <Loading text="연결된 Task를 불러오는 중..." />}
      {tasks.error && <ErrorBox error={tasks.error} onRetry={tasks.reload} />}
      {!tasks.loading && !tasks.error && tasks.data?.length === 0 && (
        <p className="m-0 text-xs text-[var(--muted)]">연결된 Task가 없습니다. Task의 협업 맥락에서 이 메시지를 자료로 연결할 수 있어요.</p>
      )}
      {!tasks.loading && !tasks.error && tasks.data?.map((task) => (
        <Link key={task.taskId} to={`/workspaces/${workspaceId}/tasks/${task.taskId}`}
          className="flex min-w-0 flex-wrap items-center justify-between gap-2 rounded-lg bg-[var(--surface-2)] p-2 text-xs no-underline">
          <strong className="min-w-0 break-words text-[var(--text)]">{task.title}</strong>
          <span className={task.status === 'DONE' ? 'text-[var(--success)]' : 'text-[var(--muted)]'}>
            {task.status === 'DONE' ? '완료' : '진행 중'} · {task.progress}%
          </span>
        </Link>
      ))}
    </div>
  );
}
