// UI-10 회의 목록 · 생성 (담당: A)
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { createMeeting, getAgendaCandidates, getMeetings, type MeetingSummaryResponse } from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { useLoginUser } from '../../components/RequireAuth';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import { addDays, formatDate, formatDateTime, startOfDay, toInputValue } from '../../utils/date';

export const STATUS_TEXT = { SCHEDULING: '일정 조율 중', CONFIRMED: '확정', CANCELED: '취소됨' } as const;

function isPast(m: MeetingSummaryResponse) {
  if (m.status === 'CANCELED') return true;
  if (m.status === 'CONFIRMED' && m.startAt) return new Date(m.startAt) < new Date();
  return new Date(m.candidateEndAt) < new Date();
}

export default function MeetingListPage() {
  const { workspaceId = '' } = useParams();
  const meetings = useAsync(() => getMeetings(workspaceId), [workspaceId]);
  const [creating, setCreating] = useState(false);
  const [tab, setTab] = useState<'upcoming' | 'past'>('upcoming');
  const list = (meetings.data ?? []).filter((m) => (tab === 'past' ? isPast(m) : !isPast(m)));

  return (
    <section className="stack">
      <div className="row">
        <h1>회의</h1>
        <span className="spacer" />
        <button className="btn btn-primary" onClick={() => setCreating(true)}>
          + 회의 만들기
        </button>
      </div>

      {creating && <MeetingCreateForm workspaceId={workspaceId} onClose={() => setCreating(false)} />}

      <div className="segmented">
        <button className={tab === 'upcoming' ? 'active' : ''} onClick={() => setTab('upcoming')}>
          예정된 회의
        </button>
        <button className={tab === 'past' ? 'active' : ''} onClick={() => setTab('past')}>
          지난 회의
        </button>
      </div>

      {meetings.loading && <Loading />}
      {meetings.error && <ErrorBox error={meetings.error} onRetry={meetings.reload} />}
      {meetings.data && list.length === 0 && (
        <Empty>{tab === 'upcoming' ? '예정된 회의가 없습니다.' : '지난 회의가 없습니다.'}</Empty>
      )}
      {list.length > 0 && (
        <ul className="list card">
          {list.map((m) => (
            <li key={m.meetingId}>
              <Link to={`/workspaces/${workspaceId}/meetings/${m.meetingId}`} className="row link-row">
                <span className={`badge status-${m.status.toLowerCase()}`}>{STATUS_TEXT[m.status]}</span>
                <strong>{m.title}</strong>
                <span className="spacer" />
                <span className="small muted">
                  {m.status === 'CONFIRMED' && m.startAt
                    ? formatDateTime(m.startAt)
                    : `${formatDate(m.candidateStartAt)} ~ ${formatDate(m.candidateEndAt)} · 응답 ${m.respondedCount}/${m.participantCount}`}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function MeetingCreateForm({ workspaceId, onClose }: { workspaceId: string; onClose: () => void }) {
  const user = useLoginUser();
  const navigate = useNavigate();
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const tasks = useAsync(() => getAgendaCandidates(workspaceId), [workspaceId]);
  const tomorrow = addDays(startOfDay(new Date()), 1);
  const [form, setForm] = useState({
    title: '',
    durationMinutes: 60,
    candidateStartAt: toInputValue(new Date(tomorrow.getTime() + 9 * 3600_000)),
    candidateEndAt: toInputValue(new Date(addDays(tomorrow, 7).getTime() + 22 * 3600_000)),
    participantIds: [user.userId],
    meetingMode: '온라인',
    agendaTaskIds: [] as number[],
  });
  const [error, setError] = useState<Error>();
  const [saving, setSaving] = useState(false);

  const toggle = (key: 'participantIds' | 'agendaTaskIds', id: number) =>
    setForm({
      ...form,
      [key]: form[key].includes(id) ? form[key].filter((x) => x !== id) : [...form[key], id],
    });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(undefined);
    try {
      const created = await createMeeting(workspaceId, {
        ...form,
        candidateStartAt: `${form.candidateStartAt}:00`,
        candidateEndAt: `${form.candidateEndAt}:00`,
      });
      navigate(`/workspaces/${workspaceId}/meetings/${created.meetingId}`);
    } catch (err) {
      setError(err as Error);
      setSaving(false);
    }
  };

  return (
    <form className="card form" onSubmit={submit}>
      <h2>회의 만들기</h2>
      <label>
        제목 *
        <input
          className="input"
          required
          maxLength={200}
          value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })}
        />
      </label>
      <div className="row">
        <label className="grow">
          소요 시간
          <select
            className="input"
            value={form.durationMinutes}
            onChange={(e) => setForm({ ...form, durationMinutes: Number(e.target.value) })}
          >
            {[30, 60, 90, 120, 180].map((m) => (
              <option key={m} value={m}>
                {m >= 60 ? `${m / 60}시간` : `${m}분`}
              </option>
            ))}
          </select>
        </label>
        <label className="grow">
          진행 방식
          <select
            className="input"
            value={form.meetingMode}
            onChange={(e) => setForm({ ...form, meetingMode: e.target.value })}
          >
            <option>온라인</option>
            <option>오프라인</option>
            <option>혼합</option>
          </select>
        </label>
      </div>
      <div className="row">
        <label className="grow">
          후보 기간 시작 *
          <input
            className="input"
            type="datetime-local"
            required
            value={form.candidateStartAt}
            onChange={(e) => setForm({ ...form, candidateStartAt: e.target.value })}
          />
        </label>
        <label className="grow">
          후보 기간 끝 *
          <input
            className="input"
            type="datetime-local"
            required
            value={form.candidateEndAt}
            onChange={(e) => setForm({ ...form, candidateEndAt: e.target.value })}
          />
        </label>
      </div>
      <fieldset>
        <legend>참여자</legend>
        {members.loading && <Loading />}
        <div className="tags">
          {members.data?.map((m) => (
            <label key={m.userId} className="tag selectable">
              <input
                type="checkbox"
                checked={form.participantIds.includes(m.userId)}
                disabled={m.userId === user.userId}
                onChange={() => toggle('participantIds', m.userId)}
              />
              {m.name}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset>
        <legend>안건 (진행 중인 Task)</legend>
        {tasks.loading && <Loading />}
        {tasks.data?.length === 0 && <span className="muted small">진행 중인 Task가 없습니다.</span>}
        <div className="tags">
          {tasks.data?.map((t) => (
            <label key={t.taskId} className="tag selectable">
              <input
                type="checkbox"
                checked={form.agendaTaskIds.includes(t.taskId)}
                onChange={() => toggle('agendaTaskIds', t.taskId)}
              />
              {t.title}
            </label>
          ))}
        </div>
      </fieldset>
      {error && <ErrorBox error={error} />}
      <div className="row">
        <button className="btn btn-primary" disabled={saving}>
          {saving ? '만드는 중...' : '만들기'}
        </button>
        <button type="button" className="btn" onClick={onClose}>
          취소
        </button>
      </div>
    </form>
  );
}
