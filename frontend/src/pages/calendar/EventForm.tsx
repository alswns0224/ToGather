// UI-07 일정 등록·수정 — 회의 만들기 화면과 같은 모양. 새 일정은 팀 일정 / 개인 일정을 고른다.
import { useEffect, useId, useState, type FormEvent } from 'react';
import { createEvent, updateEvent, type CalendarEvent, type CalendarEventForm } from './api';
import type { MemberResponse } from '../workspace/api';
import { formatDateTime, fromLocalIso, toInputValue } from '../../utils/date';
import NavIcon from '../../layouts/NavIcon';
import MeetingFormIcon from '../meeting/MeetingFormIcon';
import MeetingDateTimePicker from '../meeting/MeetingDateTimePicker';
import {
  MeetingFormShell, MeetingParticipantsField, MeetingSectionHeading, durationLabel,
  MEETING_FORM_FIELD as FIELD, MEETING_SELECTED as SELECTED, MEETING_UNSELECTED as UNSELECTED,
} from '../meeting/MeetingFormFields';

type Kind = 'TEAM' | 'PERSONAL';
const DURATIONS = [30, 60, 90, 120, 180];
const KINDS: { value: Kind; title: string; description: string; icon: 'meetings' | 'calendar' }[] = [
  { value: 'TEAM', title: '팀 일정', description: '팀 캘린더에 등록하고 참여자 캘린더에도 넣어요.', icon: 'meetings' },
  { value: 'PERSONAL', title: '개인 일정', description: '내 Google Calendar에만 등록해요. 나만 볼 수 있어요.', icon: 'calendar' },
];

const minutesBetween = (start: string, end: string) =>
  Math.round((fromLocalIso(end).getTime() - fromLocalIso(start).getTime()) / 60_000);
const plusMinutes = (value: string, minutes: number) => toInputValue(new Date(fromLocalIso(value).getTime() + minutes * 60_000));

export default function EventForm({ workspaceId, event, members, defaultDate, onClose, onSaved }: {
  workspaceId: string;
  event: CalendarEvent | null;
  members: { data?: MemberResponse[]; loading: boolean; error?: Error; reload: () => void };
  defaultDate: Date;
  onClose: () => void;
  onSaved: () => void;
}) {
  const id = useId();
  const initialStart = new Date(defaultDate);
  initialStart.setHours(10, 0, 0, 0);
  const [kind, setKind] = useState<Kind>(event?.personal ? 'PERSONAL' : 'TEAM');
  const [form, setForm] = useState(() => ({
    title: event?.title ?? '',
    startAt: event ? event.startAt.slice(0, 16) : toInputValue(initialStart),
    endAt: event ? event.endAt.slice(0, 16) : toInputValue(new Date(initialStart.getTime() + 3600_000)),
    participantIds: event
      ? (members.data ?? []).filter((m) => event.attendees.includes(m.email)).map((m) => m.userId)
      : ([] as number[]),
    description: event?.description ?? '',
  }));
  const [error, setError] = useState<Error>();
  const [saving, setSaving] = useState(false);
  const personal = kind === 'PERSONAL';
  const invalid = Boolean(form.startAt && form.endAt && form.startAt >= form.endAt);
  const duration = invalid ? 0 : minutesBetween(form.startAt, form.endAt);

  // 수정 화면을 연 뒤 팀원 목록이 늦게 오면 그때 참여자를 채운다.
  const [filled, setFilled] = useState(!event || Boolean(members.data));
  useEffect(() => {
    if (filled || !event || !members.data) return;
    setFilled(true);
    setForm((current) => ({
      ...current,
      participantIds: members.data!.filter((m) => event.attendees.includes(m.email)).map((m) => m.userId),
    }));
  }, [filled, event, members.data]);

  const patch = (next: Partial<typeof form>) => setForm((current) => ({ ...current, ...next }));

  /** 시작을 옮기면 길이는 그대로 두고 종료도 같이 옮긴다. */
  const changeStart = (startAt: string) =>
    setForm((current) => {
      const keep = current.startAt < current.endAt ? minutesBetween(current.startAt, current.endAt) : 60;
      return { ...current, startAt, endAt: plusMinutes(startAt, keep) };
    });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!form.title.trim()) { setError(new Error('일정 제목을 입력해 주세요.')); return; }
    if (invalid) { setError(new Error('종료 시각은 시작 시각보다 뒤여야 합니다.')); return; }
    setSaving(true);
    setError(undefined);
    const body: CalendarEventForm = {
      title: form.title.trim(),
      startAt: `${form.startAt}:00`,
      endAt: `${form.endAt}:00`,
      participantIds: personal ? [] : form.participantIds,
      description: form.description,
      personal,
    };
    try {
      if (event) {
        // 개인 일정은 참여자를 바꾸지 않는다. 종류는 수정에서 바꿀 수 없다.
        const { title, startAt, endAt, description, participantIds } = body;
        await updateEvent(workspaceId, event.contentRefId,
          personal ? { title, startAt, endAt, description } : { title, startAt, endAt, description, participantIds });
      } else {
        await createEvent(workspaceId, body);
      }
      onSaved();
    } catch (err) {
      setError(err as Error);
      setSaving(false);
    }
  };

  const title = event ? '일정 수정' : '새 일정 만들기';
  const chosen = KINDS.find((k) => k.value === kind)!;
  let step = 0;
  const next = () => String(++step).padStart(2, '0');

  return (
    <div className="modal-backdrop" onClick={() => !saving && onClose()}>
      <div className="max-h-[calc(100dvh-32px)] w-full max-w-[1040px] overflow-y-auto rounded-[22px]" onClick={(e) => e.stopPropagation()}>
        <MeetingFormShell
          title={title}
          description={event ? '일정 내용을 고치면 Google Calendar에도 그대로 반영돼요.' : '팀 일정인지 내 개인 일정인지 고르고, 언제인지 정해 주세요.'}
          saving={saving} onSubmit={submit} onClose={onClose} error={error}
          submitDisabled={!form.title.trim() || invalid}
          submitLabel={event ? '저장' : `${chosen.title} 등록`} savingLabel="저장 중…"
          footerHint={personal
            ? <><MeetingFormIcon name="calendar" className="h-3 w-3" />내 Google Calendar에만 저장돼요</>
            : <><span className="font-semibold text-[var(--primary)]">팀 캘린더</span><MeetingFormIcon name="arrow" className="h-3 w-3" />참여자 캘린더</>}
          sidebar={<>
            {personal ? (
              <div className="rounded-xl border border-dashed border-[var(--border)] p-4 text-[11px] leading-5 text-[var(--muted)]">
                <strong className="mb-1 block text-sm text-[var(--text)]">나만 보는 일정</strong>
                개인 일정은 참여자를 정하지 않아요. 팀원 화면에는 보이지 않고, 내 일정 화면에서 "개인 일정"으로 표시돼요.
              </div>
            ) : (
              <MeetingParticipantsField members={members} value={form.participantIds} lockedUserId={null}
                onChange={(participantIds) => patch({ participantIds })} />
            )}
            <section aria-label="일정 미리보기" className="mt-6 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4">
              <span className="text-[10px] font-bold tracking-wide text-[var(--primary)]">일정 미리보기</span>
              <h3 className="m-0 mt-2 text-[15px] leading-6 font-bold break-words">{form.title.trim() || '새 일정을 준비해요'}</h3>
              <dl className="m-0 mt-4 space-y-2.5 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <dt className="flex items-center gap-1.5 text-[var(--muted)]"><NavIcon name={chosen.icon} />종류</dt>
                  <dd className="m-0 font-semibold">{chosen.title}</dd>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <dt className="flex items-center gap-1.5 text-[var(--muted)]"><MeetingFormIcon name="clock" />길이</dt>
                  <dd className="m-0 font-semibold">{duration > 0 ? durationLabel(duration) : '-'}</dd>
                </div>
                {!personal && (
                  <div className="flex items-center justify-between gap-2">
                    <dt className="flex items-center gap-1.5 text-[var(--muted)]"><NavIcon name="meetings" />참여자</dt>
                    <dd className="m-0 font-semibold">{form.participantIds.length}명</dd>
                  </div>
                )}
              </dl>
              <div className="mt-4 border-t border-[var(--border-soft)] pt-3 text-[11px] leading-5 text-[var(--muted)]">
                <span className="font-semibold">일시</span>
                <div className="mt-1">{formatDateTime(form.startAt)}<br />~ {formatDateTime(form.endAt)}</div>
              </div>
            </section>
          </>}
        >
          {!event && (
            <section aria-label="일정 종류">
              <MeetingSectionHeading number={next()} title="일정 종류" />
              <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
                {KINDS.map((k) => {
                  const selected = kind === k.value;
                  return (
                    <label key={k.value} className={`relative flex cursor-pointer items-start gap-3 rounded-xl border p-3.5 transition focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-[var(--primary)] ${selected ? SELECTED : UNSELECTED}`}>
                      <input className="sr-only" type="radio" name={`${id}-kind`} value={k.value} checked={selected} onChange={() => setKind(k.value)} />
                      <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${selected ? 'bg-[var(--primary)] text-white' : 'bg-[var(--surface-2)] text-[var(--muted)]'}`}>
                        <NavIcon name={k.icon} className="h-[18px] w-[18px]" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-[13px] font-bold text-[var(--text)]">{k.title}</span>
                        <span className="mt-0.5 block text-[11px] leading-5 text-[var(--muted)]">{k.description}</span>
                      </span>
                      <span className={`mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border ${selected ? 'border-[var(--primary)] bg-[var(--primary)] text-white' : 'border-[var(--border)] bg-[var(--surface)]'}`}>
                        {selected && <MeetingFormIcon name="check" className="h-3 w-3" />}
                      </span>
                    </label>
                  );
                })}
              </div>
            </section>
          )}

          <section aria-label="일정 정보" className={event ? '' : 'border-t border-[var(--border-soft)] pt-5'}>
            <MeetingSectionHeading number={next()} title="일정 정보" />
            <label className="block text-xs font-semibold text-[var(--text)]">
              일정 제목 <span className="text-[var(--primary)]">*</span>
              <input className={`${FIELD} mt-2`} required maxLength={200} autoFocus
                placeholder={personal ? '예: 치과 예약' : '예: 최종 발표 리허설'}
                value={form.title} onChange={(e) => patch({ title: e.target.value })} />
            </label>
            <label className="mt-4 block text-xs font-semibold text-[var(--text)]">
              설명 <span className="ml-1 text-xs font-medium text-[var(--muted)]">선택</span>
              <textarea className={`${FIELD} mt-2 min-h-24 resize-y`} maxLength={5000}
                placeholder="장소, 준비물, 메모 등을 적어 두세요."
                value={form.description} onChange={(e) => patch({ description: e.target.value })} />
            </label>
          </section>

          <section aria-label="일시" className="border-t border-[var(--border-soft)] pt-5">
            <MeetingSectionHeading number={next()} title="일시" description="시작을 옮기면 길이는 그대로 두고 종료도 함께 옮겨요." />
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div className="min-w-0 rounded-xl bg-[var(--surface-2)] p-3 text-xs font-semibold text-[var(--muted)]">
                시작 <span className="text-[var(--primary)]">*</span>
                <MeetingDateTimePicker label="시작" disabled={saving} value={form.startAt} onChange={changeStart} />
              </div>
              <div className="min-w-0 rounded-xl bg-[var(--surface-2)] p-3 text-xs font-semibold text-[var(--muted)]">
                종료 <span className="text-[var(--primary)]">*</span>
                <MeetingDateTimePicker label="종료" disabled={saving} value={form.endAt} invalid={invalid} onChange={(endAt) => patch({ endAt })} />
              </div>
            </div>
            <fieldset className="m-0 mt-3 min-w-0 rounded-none border-0 p-0">
              <legend className="mb-2 p-0 text-xs font-semibold">길이 빠르게 고르기</legend>
              <div className="flex flex-wrap gap-2">
                {DURATIONS.map((minutes) => (
                  <label key={minutes} className={`relative flex flex-1 cursor-pointer items-center justify-center whitespace-nowrap rounded-xl border px-3 py-2.5 text-[13px] font-semibold transition focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-[var(--primary)] ${duration === minutes ? SELECTED : UNSELECTED}`}>
                    <input className="sr-only" type="radio" name={`${id}-duration`} checked={duration === minutes}
                      onChange={() => patch({ endAt: plusMinutes(form.startAt, minutes) })} />
                    {durationLabel(minutes)}
                  </label>
                ))}
              </div>
            </fieldset>
            <p className={`m-0 mt-2 text-[11px] leading-5 ${invalid ? 'text-[var(--danger)]' : 'text-[var(--muted)]'}`} role={invalid ? 'alert' : undefined}>
              {invalid ? '종료 시각은 시작 시각보다 뒤로 설정해 주세요.' : '모든 날짜와 시간은 한국 시간 기준이에요.'}
            </p>
          </section>
        </MeetingFormShell>
      </div>
    </div>
  );
}
