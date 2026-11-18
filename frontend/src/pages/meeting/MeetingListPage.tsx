// UI-10 회의 목록 · 생성 (담당: A)
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { createMeeting, getAgendaCandidates, getMeetings, type MeetingSummaryResponse } from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { useLoginUser } from '../../components/RequireAuth';
import { ErrorBox, Loading } from '../../components/Status';
import { addDays, formatDate, formatDateTime, startOfDay, toInputValue } from '../../utils/date';
import PageHeader from '../../components/PageHeader';
import { MUTED } from '../../components/ui';
import NavIcon from '../../layouts/NavIcon';

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
      <PageHeader
        icon={<NavIcon name="meetings" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="회의"
        description="안건을 모으고, 팀이 함께할 시간을 정해요."
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setCreating((v) => !v)}>
            {creating ? '닫기' : '+ 새 회의'}
          </button>
        }
      />

      <div className="tg-underline-tabs">
        {(['upcoming', 'past'] as const).map((t) => (
          <button key={t} type="button" className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
            {t === 'upcoming' ? '예정된 회의' : '지난 회의'}
          </button>
        ))}
      </div>

      {creating && <MeetingCreateForm workspaceId={workspaceId} onClose={() => setCreating(false)} />}

      {meetings.loading && <Loading />}
      {meetings.error && <ErrorBox error={meetings.error} onRetry={meetings.reload} />}
      {meetings.data && list.length === 0 && (
        <div className="card p-10 text-center">
          <p className={`m-0 text-sm ${MUTED}`}>{tab === 'upcoming' ? '예정된 회의가 없습니다. "+ 새 회의"로 만들어 보세요.' : '지난 회의가 없습니다.'}</p>
        </div>
      )}
      {list.length > 0 && (
        <div className="flex flex-col gap-3">
          {list.map((m) => (
            <Link key={m.meetingId} to={`/workspaces/${workspaceId}/meetings/${m.meetingId}`} className="card meeting-row">
              <span className="meeting-icon">
                <NavIcon name="calendar" />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <strong className="text-[17px] text-[var(--text)]">{m.title}</strong>
                  <span className={`badge ${MEETING_BADGE[m.status]}`}>{STATUS_TEXT[m.status]}</span>
                </div>
                <div className="mt-0.5 text-[13px] text-[var(--muted)]">
                  {m.durationMinutes >= 60 ? `${m.durationMinutes / 60}시간` : `${m.durationMinutes}분`}
                  {m.meetingMode && ` · ${m.meetingMode} 회의`} · 팀원 {m.participantCount}명 ·{' '}
                  {m.status === 'CONFIRMED' && m.startAt
                    ? formatDateTime(m.startAt)
                    : `후보 기간 ${formatDate(m.candidateStartAt)} ~ ${formatDate(m.candidateEndAt)}`}
                </div>
              </div>
              {m.status === 'SCHEDULING' && (
                <span className={`badge ${m.respondedCount >= m.participantCount ? 'badge-success' : ''}`}>
                  {m.respondedCount >= m.participantCount ? '✓ ' : ''}
                  {m.respondedCount}/{m.participantCount}명 응답
                </span>
              )}
              <span className="text-[var(--muted)]">›</span>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

export const MEETING_BADGE = { SCHEDULING: 'badge-warning', CONFIRMED: 'badge-success', CANCELED: '' } as const;

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
      <strong className="text-[15px]">새 회의</strong>
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
        <legend>안건 (진행 중인 팀 작업)</legend>
        {tasks.loading && <Loading />}
        {tasks.data?.length === 0 && <span className="muted small">진행 중인 팀 작업이 없습니다.</span>}
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
