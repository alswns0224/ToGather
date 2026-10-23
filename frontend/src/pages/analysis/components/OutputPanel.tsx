import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import {
  createFollowUpTask,
  createNotionDocument,
  getMeetingDraft,
  getNotionDraft,
  registerMeeting,
  type AnalysisItemResponse,
  type AnalysisResultDTO,
  type MeetingDraftDTO,
  type OutputStatus,
} from '../api';
import { getMembers, type MemberResponse } from '../../workspace/api';
import { useAsync } from '../../../hooks/useAsync';
import { formatDateTime } from '../../../utils/date';

/**
 * 확정된 분석의 실행 연계와 처리 결과 (UI-08). 단계마다 성공·실패를 보여주고, 실패한 단계만 다시 시도한다.
 */
export default function OutputPanel({
  workspaceId,
  analysis,
  onChanged,
}: {
  workspaceId: string;
  analysis: AnalysisResultDTO;
  onChanged: () => void;
}) {
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const followUps = analysis.items.filter((i) => i.itemType === 'FOLLOW_UP');
  const meetings = analysis.items.filter((i) => i.itemType === 'MEETING_PROPOSAL');

  return (
    <div className="stack">
      <h2 className="!text-lg">✅ 처리 결과</h2>

      <div className="card stack">
        <div className="row">
          <h3 className="m-0 text-sm">📄 Notion 결정사항 문서</h3>
          <StatusBadge status={analysis.notionOutputStatus} />
        </div>
        {analysis.notionOutputStatus === 'SUCCESS' && analysis.notionPageRefId ? (
          <Link to={`/workspaces/${workspaceId}/notion?page=${analysis.notionPageRefId}`}>생성된 문서 보기</Link>
        ) : (
          <NotionStep workspaceId={workspaceId} analysisId={analysis.analysisId} onDone={onChanged} />
        )}
      </div>

      {followUps.length > 0 && (
        <div className="card stack">
          <h3 className="m-0 text-sm">✅ 후속 작업 → Task</h3>
          <ul className="list analysis-output">
            {followUps.map((item) => (
              <li key={item.itemId}>
                <TaskStep workspaceId={workspaceId} item={item} members={members.data ?? []} onDone={onChanged} />
              </li>
            ))}
          </ul>
        </div>
      )}

      {meetings.length > 0 && (
        <div className="card stack">
          <h3 className="m-0 text-sm">📅 회의 제안 → Calendar 일정</h3>
          <ul className="list analysis-output">
            {meetings.map((item) => (
              <li key={item.itemId}>
                <MeetingStep workspaceId={workspaceId} item={item} members={members.data ?? []} onDone={onChanged} />
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: OutputStatus | null }) {
  if (status === 'SUCCESS') return <span className="badge badge-success">생성됨</span>;
  if (status === 'FAILED') return <span className="badge badge-danger">실패 · 다시 시도 가능</span>;
  return <span className="badge">대기</span>;
}

function NotionStep({ workspaceId, analysisId, onDone }: { workspaceId: string; analysisId: number; onDone: () => void }) {
  const [draft, setDraft] = useState<{ title: string; body: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const preview = async () => {
    setBusy(true);
    setError(null);
    try {
      setDraft(await getNotionDraft(workspaceId, analysisId));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      await createNotionDocument(workspaceId, analysisId, draft);
      onDone();
    } catch (err) {
      setError((err as Error).message); // 초안은 유지하고 다시 시도
    } finally {
      setBusy(false);
    }
  };

  if (!draft) {
    return (
      <div className="stack">
        {error && <div className="alert alert-error small">{error}</div>}
        <button className="btn btn-small" onClick={preview} disabled={busy}>
          문서 미리보기
        </button>
      </div>
    );
  }
  return (
    <form className="form" onSubmit={submit}>
      <label>
        제목
        <input
          className="input"
          required
          maxLength={200}
          value={draft.title}
          onChange={(e) => setDraft({ ...draft, title: e.target.value })}
        />
      </label>
      <label>
        본문
        <textarea
          className="input"
          style={{ minHeight: 220 }}
          required
          value={draft.body}
          onChange={(e) => setDraft({ ...draft, body: e.target.value })}
        />
      </label>
      {error && <div className="alert alert-error small">문서를 만들지 못했습니다. {error}</div>}
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn btn-small" onClick={() => setDraft(null)} disabled={busy}>
          닫기
        </button>
        <button className="btn btn-primary btn-small" disabled={busy}>
          {busy ? '만드는 중...' : error ? '다시 시도' : 'Notion 문서 만들기'}
        </button>
      </div>
    </form>
  );
}

function TaskStep({
  workspaceId,
  item,
  members,
  onDone,
}: {
  workspaceId: string;
  item: AnalysisItemResponse;
  members: MemberResponse[];
  onDone: () => void;
}) {
  const [form, setForm] = useState({
    title: item.content.slice(0, 200),
    description: item.content,
    assigneeId: item.suggestedAssigneeId,
    dueDate: item.suggestedDueDate,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (item.outputStatus === 'SUCCESS') {
    return (
      <>
        <StatusBadge status="SUCCESS" />
        <span className="grow">{item.content}</span>
        <Link to={`/workspaces/${workspaceId}/tasks/${item.outputTargetId}`}>Task 보기</Link>
      </>
    );
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await createFollowUpTask(workspaceId, item.itemId, form);
      onDone();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="form grow" onSubmit={submit}>
      <div className="row">
        <StatusBadge status={item.outputStatus} />
        <input
          className="input grow"
          required
          maxLength={200}
          value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })}
        />
      </div>
      <div className="row">
        <select
          className="input grow"
          required
          value={form.assigneeId ?? ''}
          onChange={(e) => setForm({ ...form, assigneeId: e.target.value ? Number(e.target.value) : null })}
        >
          <option value="">담당자 선택</option>
          {members.map((m) => (
            <option key={m.userId} value={m.userId}>
              {m.name}
            </option>
          ))}
        </select>
        <input
          type="date"
          className="input grow"
          required
          value={form.dueDate ?? ''}
          onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })}
        />
        <button className="btn btn-primary btn-small" disabled={busy || !form.assigneeId || !form.dueDate}>
          {busy ? '만드는 중...' : item.outputStatus === 'FAILED' ? '다시 시도' : 'Task 만들기'}
        </button>
      </div>
      {error && <div className="alert alert-error small">{error}</div>}
    </form>
  );
}

function MeetingStep({
  workspaceId,
  item,
  members,
  onDone,
}: {
  workspaceId: string;
  item: AnalysisItemResponse;
  members: MemberResponse[];
  onDone: () => void;
}) {
  const [draft, setDraft] = useState<MeetingDraftDTO | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (item.outputStatus === 'SUCCESS') {
    return (
      <>
        <StatusBadge status="SUCCESS" />
        <span className="grow">{item.content}</span>
        <Link to={`/workspaces/${workspaceId}/calendar`}>일정 보기</Link>
      </>
    );
  }

  const load = async () => {
    setBusy(true);
    setError(null);
    try {
      setDraft(await getMeetingDraft(workspaceId, item.itemId));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      await registerMeeting(workspaceId, item.itemId, draft);
      onDone();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!draft) {
    return (
      <>
        <StatusBadge status={item.outputStatus} />
        <span className="grow">
          {item.content}
          {item.suggestedDatetime && <span className="small muted"> · {formatDateTime(item.suggestedDatetime)}</span>}
        </span>
        <button className="btn btn-small" onClick={load} disabled={busy}>
          일정 등록 준비
        </button>
        {error && <div className="alert alert-error small">{error}</div>}
      </>
    );
  }

  const toggle = (id: number) =>
    setDraft({
      ...draft,
      participantIds: draft.participantIds.includes(id)
        ? draft.participantIds.filter((p) => p !== id)
        : [...draft.participantIds, id],
    });

  return (
    <form className="form grow" onSubmit={submit}>
      <input
        className="input"
        required
        maxLength={200}
        value={draft.title}
        onChange={(e) => setDraft({ ...draft, title: e.target.value })}
      />
      <div className="row">
        <label className="grow">
          시작
          <input
            type="datetime-local"
            className="input"
            required
            value={draft.startAt?.slice(0, 16) ?? ''}
            onChange={(e) => setDraft({ ...draft, startAt: e.target.value ? `${e.target.value}:00` : null })}
          />
        </label>
        <label className="grow">
          종료
          <input
            type="datetime-local"
            className="input"
            value={draft.endAt?.slice(0, 16) ?? ''}
            onChange={(e) => setDraft({ ...draft, endAt: e.target.value ? `${e.target.value}:00` : null })}
          />
        </label>
      </div>
      <div className="tags">
        {members.map((m) => (
          <button
            type="button"
            key={m.userId}
            className={`tag selectable${draft.participantIds.includes(m.userId) ? ' tag-done' : ''}`}
            onClick={() => toggle(m.userId)}
          >
            {m.name}
          </button>
        ))}
      </div>
      {error && <div className="alert alert-error small">일정을 등록하지 못했습니다. {error}</div>}
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn btn-small" onClick={() => setDraft(null)} disabled={busy}>
          닫기
        </button>
        <button className="btn btn-primary btn-small" disabled={busy || !draft.startAt}>
          {busy ? '등록 중...' : error ? '다시 시도' : '팀 캘린더에 등록'}
        </button>
      </div>
    </form>
  );
}
