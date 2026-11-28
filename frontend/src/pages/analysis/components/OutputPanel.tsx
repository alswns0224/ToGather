import DateTimePicker, {DatePicker} from '../../../components/DateTimePicker';
import { useState, type FormEvent, type ReactNode } from 'react';
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
import { useUndo } from '../../../hooks/useUndo';
import { formatDateTime, timeInput } from '../../../utils/date';
import DueTimeInput from '../../../components/DueTimeInput';
import AnalysisIcon, { type AnalysisIconName } from './AnalysisIcon';

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
    <section className="analysis-execution">
      <div className="analysis-execution-head">
        <span className="analysis-eyebrow">검토를 마쳤어요</span>
        <h3>다음 작업으로 이어가기</h3>
        <p>필요한 작업을 선택하고, 내용을 확인한 뒤 생성하세요.</p>
      </div>
      <div className="analysis-output-actions">
        <OutputDisclosure
          icon="document"
          title="결정사항을 Notion 문서로"
          description="문서 초안을 확인하고 팀에 남겨요"
          status={analysis.notionOutputStatus}
        >
          {analysis.notionOutputStatus === 'SUCCESS' && analysis.notionPageRefId ? (
            <Link to={`/workspaces/${workspaceId}/notion?page=${analysis.notionPageRefId}`}>생성된 문서 보기</Link>
          ) : (
            <NotionStep workspaceId={workspaceId} analysisId={analysis.analysisId} onDone={onChanged} />
          )}
        </OutputDisclosure>
        {followUps.map((item) => (
          <OutputDisclosure
            key={item.itemId}
            icon="task"
            title={item.content}
            description="후속 작업을 팀 작업으로"
            status={item.outputStatus}
          >
            <TaskStep workspaceId={workspaceId} item={item} members={members.data ?? []} onDone={onChanged} />
          </OutputDisclosure>
        ))}
        {meetings.map((item) => (
          <OutputDisclosure
            key={item.itemId}
            icon="calendar"
            title={item.content}
            description="회의 제안을 팀 캘린더로"
            status={item.outputStatus}
          >
            <MeetingStep workspaceId={workspaceId} item={item} members={members.data ?? []} onDone={onChanged} />
          </OutputDisclosure>
        ))}
      </div>
    </section>
  );
}

function OutputDisclosure({
  icon,
  title,
  description,
  status,
  children,
}: {
  icon: AnalysisIconName;
  title: string;
  description: string;
  status: OutputStatus | null;
  children: ReactNode;
}) {
  return (
    <details className="analysis-output-action" open={status === 'FAILED'}>
      <summary>
        <span className="analysis-output-icon">
          <AnalysisIcon name={icon} />
        </span>
        <span className="analysis-output-label">
          <strong>{title}</strong>
          <span>{description}</span>
        </span>
        <StatusBadge status={status} />
        <AnalysisIcon name="chevron" className="analysis-output-chevron h-3.5 w-3.5" />
      </summary>
      <div className="analysis-output-body">{children}</div>
    </details>
  );
}

function StatusBadge({ status }: { status: OutputStatus | null }) {
  if (status === 'SUCCESS') return <span className="badge badge-success">생성됨</span>;
  if (status === 'FAILED') return <span className="badge badge-danger">실패 · 다시 시도 가능</span>;
  return <span className="badge">대기</span>;
}

function NotionStep({
  workspaceId,
  analysisId,
  onDone,
}: {
  workspaceId: string;
  analysisId: number;
  onDone: () => void;
}) {
  const [draft, setDraft] = useState<{ title: string; body: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const undo = useUndo(draft, setDraft);

  const preview = async () => {
    setBusy(true);
    setError(null);
    try {
      setDraft(await getNotionDraft(workspaceId, analysisId));
      undo.reset();
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
      onDone(); // 서버에 저장된 "실패" 상태를 바로 다시 불러와 배지에 보여 준다.
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
    <form className="form" onSubmit={submit} onKeyDown={undo.onKeyDown}>
      <label>
        제목
        <input
          className="input"
          required
          maxLength={200}
          value={draft.title}
          onChange={(e) => {
            undo.record('title');
            setDraft({ ...draft, title: e.target.value });
          }}
        />
      </label>
      <label>
        본문
        <textarea
          className="input"
          style={{ minHeight: 220 }}
          required
          value={draft.body}
          onChange={(e) => {
            undo.record('body');
            setDraft({ ...draft, body: e.target.value });
          }}
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
    dueTime: timeInput(item.suggestedDueTime) || null,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (item.outputStatus === 'SUCCESS') {
    return (
      <>
        <Link to={`/workspaces/${workspaceId}/tasks/${item.outputTargetId}`}>팀 작업 보기</Link>
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
      onDone(); // 저장된 "실패" 상태를 바로 보여 준다.
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="form grow" aria-label="팀 작업 생성" onSubmit={submit}>
      <div className="row">
        <input
          className="input grow"
          aria-label="팀 작업 제목"
          required
          maxLength={200}
          value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })}
        />
      </div>
      <div className="row">
        <select
          className="input grow"
          aria-label="팀 작업 담당자"
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
        <DatePicker label="팀 작업 마감일" className="grow min-w-[150px]" required disabled={busy} value={form.dueDate??''} onChange={value=>setForm({...form,dueDate:value||null})}/>
        <DueTimeInput label="팀 작업 마감 시각" disabled={busy || !form.dueDate} value={form.dueTime??''} onChange={value=>setForm({...form,dueTime:value||null})}/>
        <button className="btn btn-primary btn-small" disabled={busy || !form.assigneeId || !form.dueDate}>
          {busy ? '만드는 중...' : item.outputStatus === 'FAILED' ? '다시 시도' : '팀 작업 만들기'}
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
        {item.outputTargetId != null ? (
          <Link to={`/workspaces/${workspaceId}/meetings/${item.outputTargetId}`}>회의 보기</Link>
        ) : (
          <Link to={`/workspaces/${workspaceId}/meetings`}>회의 목록</Link>
        )}
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
      onDone(); // 저장된 "실패" 상태를 바로 보여 준다.
    } finally {
      setBusy(false);
    }
  };

  if (!draft) {
    return (
      <>
        {item.suggestedDatetime && (
          <span className="small muted">예정 일시 · {formatDateTime(item.suggestedDatetime)}</span>
        )}
        <button className="btn btn-small" onClick={load} disabled={busy}>
          회의 만들기 준비
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
          <DateTimePicker label="회의 시작" disabled={busy} value={draft.startAt?.slice(0,16)??''} onChange={value=>setDraft({...draft,startAt:value?`${value}:00`:null})}/>
        </label>
        <label className="grow">
          종료
          <DateTimePicker label="회의 종료" disabled={busy} value={draft.endAt?.slice(0,16)??''} onChange={value=>setDraft({...draft,endAt:value?`${value}:00`:null})}/>
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
      <p className="m-0 text-xs text-[var(--muted)]">
        {draft.startAt
          ? '이 시간으로 확정된 회의를 만들고, 팀 캘린더와 참여자 캘린더에 Google Meet 링크와 함께 등록해요.'
          : '시작 시각을 비우면 "일정 조율 중" 회의로 만들어요. 참여자가 가능한 시간을 입력하면 추천 시간으로 확정할 수 있어요.'}
      </p>
      {error && <div className="alert alert-error small">회의를 만들지 못했습니다. {error}</div>}
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn btn-small" onClick={() => setDraft(null)} disabled={busy}>
          닫기
        </button>
        <button className="btn btn-primary btn-small" disabled={busy}>
          {busy ? '만드는 중...' : error ? '다시 시도' : draft.startAt ? '이 시간으로 회의 확정' : '시간 조율 회의 만들기'}
        </button>
      </div>
    </form>
  );
}
