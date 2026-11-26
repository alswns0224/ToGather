// UI-10 회의 생성 (담당: A) — 회의 정보·안건과 참여자·요약을 함께 보여 준다.
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { createMeeting, getAgendaCandidates } from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { useTheme } from '../../hooks/useTheme';
import { useLoginUser } from '../../components/RequireAuth';
import { ErrorBox, Loading } from '../../components/Status';
import { addDays, formatDate, formatDateTime, startOfDay, toInputValue } from '../../utils/date';
import NavIcon from '../../layouts/NavIcon';
import MeetingFormIcon from './MeetingFormIcon';
import MeetingDateTimePicker from './MeetingDateTimePicker';

const DURATIONS = [30, 60, 90, 120, 180];
const MODES = [
  { value: '온라인', icon: 'video' },
  { value: '오프라인', icon: 'location' },
  { value: '혼합', icon: 'hybrid' },
] as const;
const FIELD = 'min-w-0 w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-3 text-sm text-[var(--text)] outline-none transition placeholder:text-[var(--muted)] focus:border-[var(--primary)] focus:ring-3 focus:ring-[var(--primary-soft)]';
const CHOICE = 'relative flex cursor-pointer items-center justify-center gap-1.5 rounded-xl border px-3 py-2.5 text-[13px] font-semibold transition focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-[var(--primary)]';
const SELECTED = 'border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--primary)]';
const UNSELECTED = 'border-[var(--border)] bg-[var(--surface)] text-[var(--muted)] hover:border-[var(--primary)] hover:text-[var(--text)]';

function durationLabel(minutes: number) {
  if (minutes < 60) return `${minutes}분`;
  return `${Math.floor(minutes / 60)}시간${minutes % 60 ? ` ${minutes % 60}분` : ''}`;
}

function SectionHeading({ number, title, description }: { number: string; title: string; description?: string }) {
  return <div className="mb-4 flex items-start gap-2.5">
    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-lg bg-[var(--primary-soft)] text-[11px] font-bold text-[var(--primary)]">{number}</span>
    <div className="min-w-0">
      <h3 className="m-0 text-sm leading-6 font-bold text-[var(--text)]">{title}</h3>
      {description && <p className="m-0 mt-0.5 text-xs leading-5 text-[var(--muted)]">{description}</p>}
    </div>
  </div>;
}

export default function MeetingCreateForm({ workspaceId, onClose }: { workspaceId: string; onClose: () => void }) {
  const user = useLoginUser();
  const navigate = useNavigate();
  const { theme } = useTheme();
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
    agendaTitles: [''],
  });
  const [error, setError] = useState<Error>();
  const [saving, setSaving] = useState(false);
  const agendaTitles = form.agendaTitles.map((title) => title.trim()).filter(Boolean);
  const agendaCount = agendaTitles.length + form.agendaTaskIds.length;
  const invalidPeriod = Boolean(form.candidateStartAt && form.candidateEndAt && form.candidateStartAt >= form.candidateEndAt);
  const allMembersSelected = Boolean(members.data?.length && members.data.every((member) => form.participantIds.includes(member.userId)));

  const toggle = (key: 'participantIds' | 'agendaTaskIds', id: number) =>
    setForm((current) => ({ ...current, [key]: current[key].includes(id) ? current[key].filter((value) => value !== id) : [...current[key], id] }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!form.title.trim()) { setError(new Error('회의 제목을 입력해 주세요.')); return; }
    if (invalidPeriod) { setError(new Error('후보 기간의 끝은 시작보다 뒤여야 합니다.')); return; }
    setSaving(true);
    setError(undefined);
    try {
      const created = await createMeeting(workspaceId, {
        ...form,
        title: form.title.trim(),
        agendaTitles,
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
    <form aria-label="새 회의 만들기" aria-busy={saving} onSubmit={submit} style={{ colorScheme: theme }}
      className="overflow-hidden rounded-[22px] border border-[var(--border)] bg-[var(--surface)] text-[var(--text)] shadow-[var(--shadow)]">
      <header className="flex items-start justify-between gap-4 border-b border-[var(--border-soft)] px-5 py-5 sm:px-6">
        <div className="flex min-w-0 items-center gap-3">
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-[var(--primary-soft)] text-[var(--primary)]">
            <MeetingFormIcon name="calendar" className="h-6 w-6" />
          </span>
          <div>
            <h2 className="m-0 text-[19px] font-extrabold tracking-tight">새 회의 만들기</h2>
            <p className="m-0 mt-1 text-xs leading-5 text-[var(--muted)]">무엇을 이야기할지 정하고, 함께할 시간을 찾아보세요.</p>
          </div>
        </div>
        <button type="button" aria-label="회의 만들기 닫기" onClick={onClose} disabled={saving}
          className="flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-[var(--muted)] transition hover:bg-[var(--surface-2)] hover:text-[var(--text)] disabled:cursor-not-allowed disabled:opacity-50">
          <MeetingFormIcon name="close" />
        </button>
      </header>

      <fieldset disabled={saving} className="m-0 grid min-w-0 grid-cols-1 gap-0 rounded-none border-0 p-0 disabled:opacity-70 lg:grid-cols-[minmax(0,1fr)_260px]">
        <div className="min-w-0 space-y-6 px-5 py-6 sm:px-6">
          <section aria-label="회의 정보">
            <SectionHeading number="01" title="회의 정보" />
            <label className="block text-xs font-semibold text-[var(--text)]">
              회의 제목 <span className="text-[var(--primary)]">*</span>
              <input className={`${FIELD} mt-2`} required maxLength={200} placeholder="예: 중간 발표 최종 리허설"
                value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} />
            </label>
            <fieldset className="m-0 mt-4 min-w-0 rounded-none border-0 p-0">
              <legend className="mb-2 p-0 text-xs font-semibold">소요 시간</legend>
              <div className="flex flex-wrap gap-2">
                {DURATIONS.map((minutes) => <label key={minutes} className={`${CHOICE} flex-1 whitespace-nowrap ${form.durationMinutes === minutes ? SELECTED : UNSELECTED}`}>
                  <input className="sr-only" type="radio" name="meeting-duration" value={minutes} checked={form.durationMinutes === minutes}
                    onChange={() => setForm({ ...form, durationMinutes: minutes })} />
                  {durationLabel(minutes)}
                </label>)}
              </div>
            </fieldset>
            <fieldset className="m-0 mt-4 min-w-0 rounded-none border-0 p-0">
              <legend className="mb-2 p-0 text-xs font-semibold">진행 방식</legend>
              <div className="grid grid-cols-3 gap-2">
                {MODES.map((mode) => <label key={mode.value} className={`${CHOICE} ${form.meetingMode === mode.value ? SELECTED : UNSELECTED}`}>
                  <input className="sr-only" type="radio" name="meeting-mode" value={mode.value} checked={form.meetingMode === mode.value}
                    onChange={() => setForm({ ...form, meetingMode: mode.value })} />
                  <MeetingFormIcon name={mode.icon} />{mode.value}
                </label>)}
              </div>
            </fieldset>
          </section>

          <section aria-label="회의 후보 기간" className="border-t border-[var(--border-soft)] pt-5">
            <SectionHeading number="02" title="후보 기간" description="이 기간 안에서 참여자들이 가능한 시간을 입력해요." />
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div className="min-w-0 rounded-xl bg-[var(--surface-2)] p-3 text-xs font-semibold text-[var(--muted)]">
                시작 <span className="text-[var(--primary)]">*</span>
                <MeetingDateTimePicker label="시작" disabled={saving} value={form.candidateStartAt}
                  onChange={(value) => setForm((current) => ({ ...current, candidateStartAt: value }))} />
              </div>
              <div className="min-w-0 rounded-xl bg-[var(--surface-2)] p-3 text-xs font-semibold text-[var(--muted)]">
                종료 <span className="text-[var(--primary)]">*</span>
                <MeetingDateTimePicker label="종료" disabled={saving} value={form.candidateEndAt} invalid={invalidPeriod}
                  onChange={(value) => setForm((current) => ({ ...current, candidateEndAt: value }))} />
              </div>
            </div>
            <p className={`m-0 mt-2 text-[11px] leading-5 ${invalidPeriod ? 'text-[var(--danger)]' : 'text-[var(--muted)]'}`} role={invalidPeriod ? 'alert' : undefined}>
              {invalidPeriod ? '종료 시각은 시작 시각보다 뒤로 설정해 주세요.' : '모든 날짜와 시간은 한국 시간 기준이에요.'}
            </p>
          </section>

          <fieldset className="m-0 min-w-0 rounded-none border-0 border-t border-[var(--border-soft)] p-0 pt-5">
            <legend className="float-left mb-4 flex w-full items-start gap-2.5 p-0">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-lg bg-[var(--primary-soft)] text-[11px] font-bold text-[var(--primary)]">03</span>
              <span className="text-sm leading-6 font-bold">회의 안건 <span className="ml-1 text-xs font-medium text-[var(--muted)]">선택</span></span>
              <span className="ml-auto rounded-md bg-[var(--primary-soft)] px-2 py-0.5 text-[11px] font-bold text-[var(--primary)]">{agendaCount}개</span>
            </legend>
            <div className="clear-both">
              <p className="m-0 mb-4 text-xs leading-5 text-[var(--muted)]">논의할 내용을 직접 적거나, 기존 팀 작업을 안건으로 연결해 보세요.</p>
              <div className="space-y-2.5" role="group" aria-label="직접 입력 안건">
                <p className="m-0 text-xs font-semibold text-[var(--text)]">새 안건 직접 입력</p>
                {form.agendaTitles.map((title, index) => (
                  <div key={index} className="flex items-center gap-2">
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[var(--primary-soft)] text-[11px] font-bold text-[var(--primary)]">{index + 1}</span>
                    <input aria-label={`직접 입력 안건 ${index + 1}`} className={`${FIELD} py-2.5 text-xs`} maxLength={200}
                      placeholder={index === 0 ? '예: 발표 순서와 역할 분담 논의' : '함께 논의할 내용을 입력하세요'} value={title}
                      autoFocus={index > 0}
                      onChange={(event) => setForm((current) => ({ ...current, agendaTitles: current.agendaTitles.map((value, row) => row === index ? event.target.value : value) }))}
                      onKeyDown={(event) => {
                        if (event.key !== 'Enter') return;
                        event.preventDefault();
                        if (title.trim() && index === form.agendaTitles.length - 1 && form.agendaTitles.length < 50) {
                          setForm((current) => ({ ...current, agendaTitles: [...current.agendaTitles, ''] }));
                        }
                      }} />
                    <button type="button" aria-label={`직접 입력 안건 ${index + 1} 삭제`}
                      className="flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-[var(--muted)] transition hover:bg-[var(--danger-soft)] hover:text-[var(--danger)]"
                      onClick={() => setForm((current) => ({ ...current, agendaTitles: current.agendaTitles.length === 1 ? [''] : current.agendaTitles.filter((_, row) => row !== index) }))}>
                      <MeetingFormIcon name="close" />
                    </button>
                  </div>
                ))}
                <button type="button" disabled={form.agendaTitles.length >= 50}
                  className="cursor-pointer rounded-lg border-0 bg-[var(--primary-soft)] px-3 py-2 text-[11px] font-bold text-[var(--primary)] transition hover:brightness-95 disabled:cursor-not-allowed disabled:opacity-40"
                  onClick={() => setForm((current) => ({ ...current, agendaTitles: [...current.agendaTitles, ''] }))}>+ 안건 입력칸 추가</button>
              </div>
              <div className="mb-3 mt-5 flex items-center gap-2 border-t border-[var(--border-soft)] pt-4">
                <NavIcon name="tasks" className="h-3.5 w-3.5 text-[var(--muted)]" />
                <span className="text-xs font-semibold">기존 팀 작업 연결</span>
                <span className="ml-auto text-[11px] text-[var(--muted)]">{form.agendaTaskIds.length}개 선택</span>
              </div>
              {tasks.loading && <Loading />}
              {tasks.error && <ErrorBox error={tasks.error} onRetry={tasks.reload} />}
              {tasks.data?.length === 0 && <p className="m-0 rounded-xl border border-dashed border-[var(--border)] p-5 text-center text-xs leading-5 text-[var(--muted)]">연결할 팀 작업이 없어요. 위에서 새 안건을 직접 입력할 수 있어요.</p>}
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {tasks.data?.map((task) => {
                  const selected = form.agendaTaskIds.includes(task.taskId);
                  return <label key={task.taskId} className={`relative flex min-w-0 cursor-pointer items-start gap-2.5 rounded-xl border p-3 transition focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-[var(--primary)] ${selected ? SELECTED : UNSELECTED}`}>
                    <input className="sr-only" type="checkbox" checked={selected} onChange={() => toggle('agendaTaskIds', task.taskId)} />
                    <span className={`mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-[5px] border ${selected ? 'border-[var(--primary)] bg-[var(--primary)] text-white' : 'border-[var(--border)] bg-[var(--surface)]'}`}>
                      {selected && <MeetingFormIcon name="check" className="h-3 w-3" />}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-xs leading-5 font-semibold text-[var(--text)]" title={task.title}>{task.title}</span>
                      <span className="mt-1 block truncate text-[11px] text-[var(--muted)]">진행률 {Math.round(task.progress)}%{task.dueDate ? ` · ${formatDate(task.dueDate)} 마감` : ' · 마감 미정'}</span>
                    </span>
                  </label>;
                })}
              </div>
            </div>
          </fieldset>
        </div>

        <aside className="min-w-0 border-t border-[var(--border-soft)] bg-[var(--surface-2)]/50 px-5 py-6 lg:border-t-0 lg:border-l">
          <fieldset className="m-0 min-w-0 rounded-none border-0 p-0">
            <legend className="mb-2 flex w-full items-center gap-2 p-0 text-sm font-bold">
              <NavIcon name="meetings" className="h-4 w-4 text-[var(--muted)]" />참여자
              <span className="ml-auto rounded-md bg-[var(--primary-soft)] px-2 py-0.5 text-[11px] font-bold text-[var(--primary)]">{form.participantIds.length}명</span>
            </legend>
            <div className="mb-3 flex items-center justify-between gap-2">
              <p className="m-0 text-[11px] text-[var(--muted)]">함께할 팀원을 선택하세요.</p>
              {members.data && members.data.length > 1 && <button type="button" className="shrink-0 cursor-pointer border-0 bg-transparent p-0 text-[11px] font-semibold text-[var(--primary)]"
                onClick={() => setForm({ ...form, participantIds: allMembersSelected ? [user.userId] : [...new Set([user.userId, ...members.data!.map((member) => member.userId)])] })}>
                {allMembersSelected ? '나만 선택' : '모두 선택'}
              </button>}
            </div>
            {members.loading && <Loading />}
            {members.error && <ErrorBox error={members.error} onRetry={members.reload} />}
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-1">
              {members.data?.map((member) => {
                const selected = form.participantIds.includes(member.userId);
                const mine = member.userId === user.userId;
                return <label key={member.userId} className={`relative flex min-w-0 items-center gap-2.5 rounded-xl border px-3 py-2.5 transition focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-[var(--primary)] ${mine ? 'cursor-default' : 'cursor-pointer'} ${selected ? SELECTED : UNSELECTED}`}>
                  <input className="sr-only" type="checkbox" checked={selected} disabled={mine} onChange={() => toggle('participantIds', member.userId)} />
                  <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold ${selected ? 'bg-[var(--primary)] text-white' : 'bg-[var(--surface-2)] text-[var(--muted)]'}`}>{member.name.slice(0, 1)}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-xs font-semibold text-[var(--text)]">{member.name}</span>
                    {mine && <span className="mt-0.5 block text-[10px] text-[var(--muted)]">나 · 만든 사람</span>}
                  </span>
                  <span className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-[5px] border ${selected ? 'border-[var(--primary)] bg-[var(--primary)] text-white' : 'border-[var(--border)] bg-[var(--surface)]'}`}>
                    {selected && <MeetingFormIcon name="check" className="h-3 w-3" />}
                  </span>
                </label>;
              })}
            </div>
          </fieldset>

          <section aria-label="선택한 회의 요약" className="mt-6 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4">
            <span className="text-[10px] font-bold tracking-wide text-[var(--primary)]">회의 미리보기</span>
            <h3 className="m-0 mt-2 text-[15px] leading-6 font-bold break-words">{form.title.trim() || '새로운 회의를 준비해요'}</h3>
            <dl className="m-0 mt-4 space-y-2.5 text-xs">
              <div className="flex items-center justify-between gap-2"><dt className="flex items-center gap-1.5 text-[var(--muted)]"><MeetingFormIcon name="clock" />소요 시간</dt><dd className="m-0 font-semibold">{durationLabel(form.durationMinutes)}</dd></div>
              <div className="flex items-center justify-between gap-2"><dt className="flex items-center gap-1.5 text-[var(--muted)]"><MeetingFormIcon name={MODES.find((mode) => mode.value === form.meetingMode)!.icon} />진행 방식</dt><dd className="m-0 font-semibold">{form.meetingMode}</dd></div>
              <div className="flex items-center justify-between gap-2"><dt className="flex items-center gap-1.5 text-[var(--muted)]"><NavIcon name="tasks" />회의 안건</dt><dd className="m-0 font-semibold">{agendaCount}개</dd></div>
            </dl>
            <div className="mt-4 border-t border-[var(--border-soft)] pt-3 text-[11px] leading-5 text-[var(--muted)]">
              <span className="font-semibold">후보 기간</span>
              <div className="mt-1">{form.candidateStartAt ? formatDateTime(form.candidateStartAt) : '시작 시각 선택'}<br />~ {form.candidateEndAt ? formatDateTime(form.candidateEndAt) : '종료 시각 선택'}</div>
            </div>
          </section>
          <p className="m-0 mt-3 px-1 text-[11px] leading-5 text-[var(--muted)]">회의를 만들면 참여자들이 가능한 시간을 입력하고, 추천 시간 중에서 일정을 확정할 수 있어요.</p>
        </aside>
      </fieldset>

      <footer className="border-t border-[var(--border-soft)] px-5 py-4 sm:px-6">
        {error && <div className="mb-3"><ErrorBox error={error} /></div>}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="flex items-center gap-2 text-[11px] text-[var(--muted)]"><span className="font-semibold text-[var(--primary)]">회의 준비</span><MeetingFormIcon name="arrow" className="h-3 w-3" />시간 조율<MeetingFormIcon name="arrow" className="h-3 w-3" />일정 확정</span>
          <div className="flex w-full items-center justify-end gap-2 sm:w-auto">
            <button type="button" onClick={onClose} disabled={saving} className="h-10 cursor-pointer rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 text-xs font-semibold text-[var(--muted)] transition hover:bg-[var(--surface-2)] disabled:cursor-not-allowed disabled:opacity-50">취소</button>
            <button type="submit" disabled={saving || !form.title.trim() || members.loading || Boolean(members.error) || invalidPeriod}
              className="flex h-10 flex-1 cursor-pointer items-center justify-center gap-2 rounded-xl border-0 bg-[image:var(--brand-grad)] px-5 text-xs font-bold text-white shadow-sm transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-45 sm:flex-none">
              {saving ? '회의 만드는 중…' : '회의 만들기'}<MeetingFormIcon name="arrow" />
            </button>
          </div>
        </div>
      </footer>
    </form>
  );
}
