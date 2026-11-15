import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { ErrorBox, Loading } from '../../components/Status';
import { CARD, MUTED, SUB_CARD } from '../../components/ui';
import { useAsync } from '../../hooks/useAsync';
import { formatDateTime } from '../../utils/date';
import { STATUS_TEXT } from '../meeting/MeetingListPage';
import {
  getTasksByContent,
  type TaskContext,
  type TaskContextContentState,
  type TaskReference,
  type TaskReferenceProvider,
} from './api';

export const SOURCES: Record<TaskReferenceProvider, string> = {
  SLACK: 'Slack',
  NOTION: 'Notion',
  GOOGLE_DRIVE: 'Google Drive',
};

export function ContextContentCard({
  workspaceId, reference, state, action,
}: {
  workspaceId: string;
  reference: TaskReference;
  state?: TaskContextContentState;
  action?: ReactNode;
}) {
  const [showTasks, setShowTasks] = useState(false);
  const title = reference.title?.trim() || reference.content?.trim().slice(0, 100) || '제목 없음';
  const url = !state?.originDeleted && reference.deepLink && /^https?:\/\//i.test(reference.deepLink)
    ? reference.deepLink : null;
  const unavailableText = reference.provider === 'GOOGLE_DRIVE'
    ? (state?.driveTrashed ? '휴지통으로 이동됨' : '연동 폴더에서 원본을 확인할 수 없음')
    : '원본 삭제됨';

  return (
    <article className={`${SUB_CARD} min-w-0 space-y-3 p-4`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 break-words">
          <p className={`m-0 mb-1 flex flex-wrap gap-x-3 gap-y-1 text-xs ${MUTED}`}>
            <span>{SOURCES[reference.provider as TaskReferenceProvider] ?? reference.provider}</span>
            <span>{state?.createdAt ? `생성 ${formatDateTime(state.createdAt)}` : '생성 시각 미상'}</span>
          </p>
          {url
            ? <a href={url} target="_blank" rel="noreferrer">{title}</a>
            : <span>{title}</span>}
        </div>
        {action}
      </div>
      {state?.originDeleted && (
        <p className="m-0 text-sm text-amber-600 dark:text-amber-400">
          {unavailableText} · 마지막으로 보관한 내용을 확인할 수 있습니다.
        </p>
      )}
      <details className="text-sm">
        <summary className="cursor-pointer font-semibold">
          {state?.originDeleted ? '보관 사본 보기' : '내용 보기'}
        </summary>
        <p className="mb-0 max-h-72 overflow-auto whitespace-pre-wrap break-words [overflow-wrap:anywhere]">
          {reference.content?.trim() || '보관된 본문이 없습니다.'}
        </p>
        {reference.provider === 'GOOGLE_DRIVE' && (
          <p className={`mb-0 text-xs ${MUTED}`}>Drive 파일은 보관된 파일 정보를 표시합니다.</p>
        )}
      </details>
      <details className="text-sm" onToggle={event => setShowTasks(event.currentTarget.open)}>
        <summary className="cursor-pointer font-semibold">연결된 Task 보기</summary>
        {showTasks && <LinkedTasks workspaceId={workspaceId} contentRefId={reference.contentRefId} />}
      </details>
    </article>
  );
}

function LinkedTasks({ workspaceId, contentRefId }: { workspaceId: string; contentRefId: number }) {
  const tasks = useAsync(() => getTasksByContent(workspaceId, contentRefId), [workspaceId, contentRefId]);
  if (tasks.loading) return <Loading text="연결된 Task를 불러오는 중..." />;
  if (tasks.error) return <ErrorBox error={tasks.error} onRetry={tasks.reload} />;
  if (!tasks.data?.length) return <p className={MUTED}>연결된 Task가 없습니다.</p>;
  return (
    <ul className="mb-0 list-none space-y-2 pl-0">
      {tasks.data.map(task => (
        <li key={task.taskId}>
          <Link to={`/workspaces/${workspaceId}/tasks/${task.taskId}`}>
            {task.title}
          </Link>
          <span className={`ml-2 text-xs ${MUTED}`}>
            {task.status === 'DONE' ? '완료' : '진행 중'} · {task.progress}%
          </span>
        </li>
      ))}
    </ul>
  );
}

export default function TaskContextPanels({ workspaceId, context }: { workspaceId: string; context: TaskContext }) {
  return (
    <div className="grid gap-5 xl:grid-cols-2">
      <section className={`${CARD} min-w-0 space-y-4 p-6`}>
        <h2 className="m-0 text-lg">Task 생성 배경과 근거</h2>
        {context.originDecision ? (
          <>
            <div className={`${SUB_CARD} space-y-2 p-4`}>
              <h3 className={`m-0 text-xs ${MUTED}`}>Task 생성의 근거가 된 분석 항목</h3>
              <p className="m-0 whitespace-pre-wrap break-words [overflow-wrap:anywhere]">
                {context.originDecision.content}
              </p>
            </div>
            <h3 className="m-0 text-sm font-semibold">근거 대화·자료</h3>
            {context.originEvidence.length === 0 && <p className={`text-sm ${MUTED}`}>연결된 근거 자료가 없습니다.</p>}
            <ul className="m-0 list-none space-y-2 p-0">
              {context.originEvidence.map(reference => (
                <li key={reference.contentRefId}>
                  <ContextContentCard workspaceId={workspaceId} reference={reference}
                    state={context.contentStates[reference.contentRefId]} />
                </li>
              ))}
            </ul>
          </>
        ) : <p className={`text-sm ${MUTED}`}>연결된 분석 항목이 없습니다.</p>}
      </section>
      <section className={`${CARD} min-w-0 space-y-4 p-6`}>
        <h2 className="m-0 text-lg">연결된 회의</h2>
        {context.meetings.length === 0 && <p className={`text-sm ${MUTED}`}>이 Task를 안건으로 연결한 회의가 없습니다.</p>}
        <ul className="m-0 list-none space-y-2 p-0">
          {context.meetings.map(meeting => (
            <li key={meeting.meetingId} className={`${SUB_CARD} space-y-2 p-4`}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <Link className="min-w-0 break-words" to={`/workspaces/${workspaceId}/meetings/${meeting.meetingId}`}>
                  {meeting.title}
                </Link>
                <span className={`badge status-${meeting.status.toLowerCase()}`}>{STATUS_TEXT[meeting.status]}</span>
              </div>
              <p className={`m-0 text-xs ${MUTED}`}>
                {meeting.startAt ? formatDateTime(meeting.startAt) : '회의 시간 미정'}
              </p>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
