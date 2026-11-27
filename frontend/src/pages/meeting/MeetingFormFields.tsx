import { useId, type FormEventHandler, type ReactNode } from 'react';
import { useTheme } from '../../hooks/useTheme';
import { useLoginUser } from '../../components/RequireAuth';
import { ErrorBox, Loading } from '../../components/Status';
import { formatDateTime } from '../../utils/date';
import type { MemberResponse } from '../workspace/api';
import NavIcon from '../../layouts/NavIcon';
import MeetingFormIcon from './MeetingFormIcon';
import MeetingDateTimePicker from './MeetingDateTimePicker';

export const MEETING_FORM_FIELD = 'min-w-0 w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-3 text-sm text-[var(--text)] outline-none transition placeholder:text-[var(--muted)] focus:border-[var(--primary)] focus:ring-3 focus:ring-[var(--primary-soft)]';
export const MEETING_SELECTED = 'border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--primary)]';
export const MEETING_UNSELECTED = 'border-[var(--border)] bg-[var(--surface)] text-[var(--muted)] hover:border-[var(--primary)] hover:text-[var(--text)]';
const CHOICE = 'relative flex cursor-pointer items-center justify-center gap-1.5 rounded-xl border px-3 py-2.5 text-[13px] font-semibold transition focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-[var(--primary)]';
const DURATIONS = [30, 60, 90, 120, 180];
const MODES = [
  { value: '온라인', icon: 'video' },
  { value: '오프라인', icon: 'location' },
  { value: '혼합', icon: 'hybrid' },
] as const;

export interface MeetingFormValues {
  title: string;
  durationMinutes: number;
  meetingMode: string;
  candidateStartAt: string;
  candidateEndAt: string;
  participantIds: number[];
}

export function durationLabel(minutes: number) {
  return minutes < 60 ? `${minutes}분` : `${Math.floor(minutes / 60)}시간${minutes % 60 ? ` ${minutes % 60}분` : ''}`;
}

export function MeetingSectionHeading({ number, title, description }: { number: string; title: string; description?: string }) {
  return <div className="mb-4 flex items-start gap-2.5">
    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-lg bg-[var(--primary-soft)] text-[11px] font-bold text-[var(--primary)]">{number}</span>
    <div className="min-w-0">
      <h3 className="m-0 text-sm leading-6 font-bold text-[var(--text)]">{title}</h3>
      {description && <p className="m-0 mt-0.5 text-xs leading-5 text-[var(--muted)]">{description}</p>}
    </div>
  </div>;
}

export function MeetingFormShell({ title, description, saving, onSubmit, onClose, children, sidebar, error, submitDisabled,
  submitLabel, savingLabel, footerHint }: {
  title: string; description: string; saving: boolean; onSubmit: FormEventHandler<HTMLFormElement>; onClose: () => void;
  children: ReactNode; sidebar: ReactNode; error?: Error; submitDisabled: boolean;
  submitLabel: string; savingLabel: string; footerHint: ReactNode;
}) {
  const { theme } = useTheme();
  return <form aria-label={title} aria-busy={saving} onSubmit={onSubmit} style={{ colorScheme: theme }}
    className="overflow-hidden rounded-[22px] border border-[var(--border)] bg-[var(--surface)] text-[var(--text)] shadow-[var(--shadow)]">
    <header className="flex items-start justify-between gap-4 border-b border-[var(--border-soft)] px-5 py-5 sm:px-6">
      <div className="flex min-w-0 items-center gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-[var(--primary-soft)] text-[var(--primary)]"><MeetingFormIcon name="calendar" className="h-6 w-6" /></span>
        <div>
          <h2 className="m-0 text-[19px] font-extrabold tracking-tight">{title}</h2>
          <p className="m-0 mt-1 text-xs leading-5 text-[var(--muted)]">{description}</p>
        </div>
      </div>
      <button type="button" aria-label={`${title} 닫기`} onClick={onClose} disabled={saving}
        className="flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-[var(--muted)] transition hover:bg-[var(--surface-2)] hover:text-[var(--text)] disabled:cursor-not-allowed disabled:opacity-50"><MeetingFormIcon name="close" /></button>
    </header>
    <fieldset disabled={saving} className="m-0 grid min-w-0 grid-cols-1 gap-0 rounded-none border-0 p-0 disabled:opacity-70 lg:grid-cols-[minmax(0,1fr)_260px]">
      <div className="min-w-0 space-y-6 px-5 py-6 sm:px-6">{children}</div>
      <aside className="min-w-0 border-t border-[var(--border-soft)] bg-[var(--surface-2)]/50 px-5 py-6 lg:border-t-0 lg:border-l">{sidebar}</aside>
    </fieldset>
    <footer className="border-t border-[var(--border-soft)] px-5 py-4 sm:px-6">
      {error && <div className="mb-3"><ErrorBox error={error} /></div>}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className="flex items-center gap-2 text-[11px] text-[var(--muted)]">{footerHint}</span>
        <div className="flex w-full items-center justify-end gap-2 sm:w-auto">
          <button type="button" onClick={onClose} disabled={saving} className="h-10 cursor-pointer rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 text-xs font-semibold text-[var(--muted)] transition hover:bg-[var(--surface-2)] disabled:cursor-not-allowed disabled:opacity-50">취소</button>
          <button type="submit" disabled={saving || submitDisabled}
            className="flex h-10 flex-1 cursor-pointer items-center justify-center gap-2 rounded-xl border-0 bg-[image:var(--brand-grad)] px-5 text-xs font-bold text-white shadow-sm transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-45 sm:flex-none">
            {saving ? savingLabel : submitLabel}<MeetingFormIcon name="arrow" />
          </button>
        </div>
      </div>
    </footer>
  </form>;
}

export function MeetingInformationFields({ value, onChange, allowUnspecified = false }: {
  value: Pick<MeetingFormValues, 'title' | 'durationMinutes' | 'meetingMode'>;
  onChange: (patch: Partial<MeetingFormValues>) => void; allowUnspecified?: boolean;
}) {
  const id = useId();
  const durations = DURATIONS.includes(value.durationMinutes) ? DURATIONS : [...DURATIONS, value.durationMinutes];
  const customMode = value.meetingMode && !MODES.some((mode) => mode.value === value.meetingMode);
  const modes = [...(allowUnspecified ? [{ value: '', icon: 'hybrid' as const }] : []), ...MODES,
    ...(customMode ? [{ value: value.meetingMode, icon: 'hybrid' as const }] : [])];
  return <section aria-label="회의 정보">
    <MeetingSectionHeading number="01" title="회의 정보" />
    <label className="block text-xs font-semibold text-[var(--text)]">
      회의 제목 <span className="text-[var(--primary)]">*</span>
      <input className={`${MEETING_FORM_FIELD} mt-2`} required maxLength={200} placeholder="예: 중간 발표 최종 리허설"
        value={value.title} onChange={(event) => onChange({ title: event.target.value })} />
    </label>
    <fieldset className="m-0 mt-4 min-w-0 rounded-none border-0 p-0">
      <legend className="mb-2 p-0 text-xs font-semibold">소요 시간</legend>
      <div className="flex flex-wrap gap-2">
        {durations.map((minutes) => <label key={minutes} className={`${CHOICE} flex-1 whitespace-nowrap ${value.durationMinutes === minutes ? MEETING_SELECTED : MEETING_UNSELECTED}`}>
          <input className="sr-only" type="radio" name={`${id}-duration`} value={minutes} checked={value.durationMinutes === minutes} onChange={() => onChange({ durationMinutes: minutes })} />{durationLabel(minutes)}
        </label>)}
      </div>
    </fieldset>
    <fieldset className="m-0 mt-4 min-w-0 rounded-none border-0 p-0">
      <legend className="mb-2 p-0 text-xs font-semibold">진행 방식</legend>
      <div className={`grid gap-2 ${modes.length === 3 ? 'grid-cols-3' : 'grid-cols-2 sm:grid-cols-4'}`}>
        {modes.map((mode) => <label key={mode.value} className={`${CHOICE} ${value.meetingMode === mode.value ? MEETING_SELECTED : MEETING_UNSELECTED}`}>
          <input className="sr-only" type="radio" name={`${id}-mode`} value={mode.value} checked={value.meetingMode === mode.value} onChange={() => onChange({ meetingMode: mode.value })} />
          <MeetingFormIcon name={mode.icon} />{mode.value || '미정'}
        </label>)}
      </div>
    </fieldset>
  </section>;
}

export function MeetingCandidatePeriodFields({ value, onChange, saving, invalid }: {
  value: Pick<MeetingFormValues, 'candidateStartAt' | 'candidateEndAt'>;
  onChange: (patch: Partial<MeetingFormValues>) => void; saving: boolean; invalid: boolean;
}) {
  return <section aria-label="회의 후보 기간" className="border-t border-[var(--border-soft)] pt-5">
    <MeetingSectionHeading number="02" title="후보 기간" description="이 기간 안에서 참여자들이 가능한 시간을 입력해요." />
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <div className="min-w-0 rounded-xl bg-[var(--surface-2)] p-3 text-xs font-semibold text-[var(--muted)]">
        시작 <span className="text-[var(--primary)]">*</span>
        <MeetingDateTimePicker label="시작" disabled={saving} value={value.candidateStartAt} onChange={(candidateStartAt) => onChange({ candidateStartAt })} />
      </div>
      <div className="min-w-0 rounded-xl bg-[var(--surface-2)] p-3 text-xs font-semibold text-[var(--muted)]">
        종료 <span className="text-[var(--primary)]">*</span>
        <MeetingDateTimePicker label="종료" disabled={saving} value={value.candidateEndAt} invalid={invalid} onChange={(candidateEndAt) => onChange({ candidateEndAt })} />
      </div>
    </div>
    <p className={`m-0 mt-2 text-[11px] leading-5 ${invalid ? 'text-[var(--danger)]' : 'text-[var(--muted)]'}`} role={invalid ? 'alert' : undefined}>
      {invalid ? '종료 시각은 시작 시각보다 뒤로 설정해 주세요.' : '모든 날짜와 시간은 한국 시간 기준이에요.'}
    </p>
  </section>;
}

export function MeetingParticipantsField({ members, value, onChange, lockedUserId }: {
  members: { data?: MemberResponse[]; loading: boolean; error?: Error; reload: () => void };
  value: number[]; onChange: (ids: number[]) => void; lockedUserId: number | null;
}) {
  const user = useLoginUser();
  const allSelected = Boolean(members.data?.length && members.data.every((member) => value.includes(member.userId)));
  return <fieldset className="m-0 min-w-0 rounded-none border-0 p-0">
    <legend className="mb-2 flex w-full items-center gap-2 p-0 text-sm font-bold">
      <NavIcon name="meetings" className="h-4 w-4 text-[var(--muted)]" />참여자
      <span className="ml-auto rounded-md bg-[var(--primary-soft)] px-2 py-0.5 text-[11px] font-bold text-[var(--primary)]">{value.length}명</span>
    </legend>
    <div className="mb-3 flex items-center justify-between gap-2">
      <p className="m-0 text-[11px] text-[var(--muted)]">함께할 팀원을 선택하세요.</p>
      {members.data && members.data.length > 1 && <button type="button" className="shrink-0 cursor-pointer border-0 bg-transparent p-0 text-[11px] font-semibold text-[var(--primary)]"
        onClick={() => onChange(allSelected ? (lockedUserId == null ? [] : [lockedUserId]) : [...new Set([...(lockedUserId == null ? [] : [lockedUserId]), ...members.data!.map((member) => member.userId)])])}>
        {allSelected ? lockedUserId === user.userId ? '나만 선택' : lockedUserId == null ? '선택 해제' : '만든 사람만 선택' : '모두 선택'}
      </button>}
    </div>
    {members.loading && <Loading />}
    {members.error && <ErrorBox error={members.error} onRetry={members.reload} />}
    <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-1">
      {members.data?.map((member) => {
        const selected = value.includes(member.userId);
        const fixed = member.userId === lockedUserId;
        return <label key={member.userId} className={`relative flex min-w-0 items-center gap-2.5 rounded-xl border px-3 py-2.5 transition focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-[var(--primary)] ${fixed ? 'cursor-default' : 'cursor-pointer'} ${selected ? MEETING_SELECTED : MEETING_UNSELECTED}`}>
          <input className="sr-only" type="checkbox" checked={selected} disabled={fixed} onChange={() => onChange(selected ? value.filter((id) => id !== member.userId) : [...value, member.userId])} />
          <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold ${selected ? 'bg-[var(--primary)] text-white' : 'bg-[var(--surface-2)] text-[var(--muted)]'}`}>{member.name.slice(0, 1)}</span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-xs font-semibold text-[var(--text)]">{member.name}</span>
            {fixed && <span className="mt-0.5 block text-[10px] text-[var(--muted)]">{member.userId === user.userId ? '나 · ' : ''}만든 사람</span>}
          </span>
          <span className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-[5px] border ${selected ? 'border-[var(--primary)] bg-[var(--primary)] text-white' : 'border-[var(--border)] bg-[var(--surface)]'}`}>{selected && <MeetingFormIcon name="check" className="h-3 w-3" />}</span>
        </label>;
      })}
    </div>
  </fieldset>;
}

export function MeetingFormSummary({ value, agendaCount, startAt, endAt, confirmed = false }: {
  value: Pick<MeetingFormValues, 'title' | 'durationMinutes' | 'meetingMode'>;
  agendaCount: number; startAt: string; endAt: string; confirmed?: boolean;
}) {
  return <section aria-label="선택한 회의 요약" className="mt-6 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4">
    <span className="text-[10px] font-bold tracking-wide text-[var(--primary)]">회의 미리보기</span>
    <h3 className="m-0 mt-2 text-[15px] leading-6 font-bold break-words">{value.title.trim() || '새로운 회의를 준비해요'}</h3>
    <dl className="m-0 mt-4 space-y-2.5 text-xs">
      <div className="flex items-center justify-between gap-2"><dt className="flex items-center gap-1.5 text-[var(--muted)]"><MeetingFormIcon name="clock" />소요 시간</dt><dd className="m-0 font-semibold">{durationLabel(value.durationMinutes)}</dd></div>
      <div className="flex items-center justify-between gap-2"><dt className="flex items-center gap-1.5 text-[var(--muted)]"><MeetingFormIcon name={MODES.find((mode) => mode.value === value.meetingMode)?.icon ?? 'hybrid'} />진행 방식</dt><dd className="m-0 font-semibold">{value.meetingMode || '미정'}</dd></div>
      <div className="flex items-center justify-between gap-2"><dt className="flex items-center gap-1.5 text-[var(--muted)]"><NavIcon name="tasks" />회의 안건</dt><dd className="m-0 font-semibold">{agendaCount}개</dd></div>
    </dl>
    <div className="mt-4 border-t border-[var(--border-soft)] pt-3 text-[11px] leading-5 text-[var(--muted)]">
      <span className="font-semibold">{confirmed ? '확정 일정' : '후보 기간'}</span>
      <div className="mt-1">{startAt ? formatDateTime(startAt) : '시작 시각 선택'}<br />~ {endAt ? formatDateTime(endAt) : '종료 시각 선택'}</div>
    </div>
  </section>;
}
