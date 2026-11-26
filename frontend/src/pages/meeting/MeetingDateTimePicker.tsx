// UI-10 회의 후보 날짜·시간 선택 — 테마를 따르는 달력 팝업.
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { useTheme } from '../../hooks/useTheme';
import MeetingFormIcon from './MeetingFormIcon';

const pad = (value: number) => String(value).padStart(2, '0');
const dayKey = (date: Date) => `${String(date.getFullYear()).padStart(4, '0')}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
const parseDay = (value: string) => {
  const [year, month, day] = value.slice(0, 10).split('-').map(Number);
  return new Date(year, month - 1, day, 12);
};
const moveDay = (date: Date, count: number) => new Date(date.getFullYear(), date.getMonth(), date.getDate() + count, 12);
const monthStart = (date: Date) => new Date(date.getFullYear(), date.getMonth(), 1, 12);
const todayInSeoul = () => {
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (type: string) => parts.find((value) => value.type === type)!.value;
  return parseDay(`${part('year')}-${part('month')}-${part('day')}`);
};
const dayLabel = (date: Date) => new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(date);
const validTimePart = (value: string, limit: number) => /^\d{1,2}$/.test(value) && Number(value) < limit;

export default function MeetingDateTimePicker({ label, value, onChange, disabled = false, invalid = false }: {
  label: string; value: string; onChange: (value: string) => void; disabled?: boolean; invalid?: boolean;
}) {
  const id = useId();
  const { theme } = useTheme();
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [month, setMonth] = useState(() => monthStart(parseDay(value)));
  const [hour, setHour] = useState(value.slice(11, 13));
  const [minute, setMinute] = useState(value.slice(14, 16));
  const [position, setPosition] = useState({ left: 8, top: 8 });
  const selected = parseDay(value);
  const selectedKey = value.slice(0, 10);
  const today = todayInSeoul();
  const todayKey = dayKey(today);
  const first = moveDay(month, -month.getDay());
  const days = Array.from({ length: 42 }, (_, index) => moveDay(first, index));
  const selectedVisible = days.some((day) => dayKey(day) === selectedKey);
  const timeValid = validTimePart(hour, 24) && validTimePart(minute, 60);
  const iconButton = 'flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-[var(--muted)] transition hover:bg-[var(--surface-2)] hover:text-[var(--primary)] focus-visible:outline-2 focus-visible:outline-[var(--primary)]';

  const close = useCallback((restoreFocus = false) => {
    setOpen(false);
    if (restoreFocus) trigger.current?.focus({ preventScroll: true });
  }, []);

  function show() {
    if (disabled) return;
    setMonth(monthStart(selected));
    setHour(value.slice(11, 13));
    setMinute(value.slice(14, 16));
    setOpen(true);
  }

  useEffect(() => { if (disabled) close(); }, [disabled, close]);

  useLayoutEffect(() => {
    if (!open || !panel.current || !trigger.current) return;
    const anchor = trigger.current.getBoundingClientRect();
    const width = panel.current.offsetWidth;
    const height = panel.current.offsetHeight;
    const viewportWidth = document.documentElement.clientWidth;
    const viewportHeight = window.innerHeight;
    const below = anchor.bottom + 8;
    const above = anchor.top - height - 8;
    setPosition({
      left: Math.max(8, Math.min(anchor.left, viewportWidth - width - 8)),
      top: Math.max(8, Math.min(below + height <= viewportHeight - 8 ? below : above >= 8 ? above : below, viewportHeight - height - 8)),
    });
  }, [open, month, timeValid]);

  useEffect(() => {
    if (!open) return;
    panel.current?.querySelector<HTMLButtonElement>(`button[data-calendar-date="${selectedKey}"]`)?.focus({ preventScroll: true });
  }, [open, selectedKey]);

  useEffect(() => {
    if (!open) return;
    const outside = (event: Event) => {
      const target = event.target;
      if (target instanceof Node && !panel.current?.contains(target) && !trigger.current?.contains(target)) close();
    };
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); close(true); }
    };
    const resize = () => close();
    document.addEventListener('pointerdown', outside);
    document.addEventListener('focusin', outside);
    document.addEventListener('keydown', escape);
    window.addEventListener('scroll', outside, true);
    window.addEventListener('resize', resize);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('focusin', outside);
      document.removeEventListener('keydown', escape);
      window.removeEventListener('scroll', outside, true);
      window.removeEventListener('resize', resize);
    };
  }, [open, close]);

  function chooseDay(date: Date) {
    onChange(`${dayKey(date)}T${value.slice(11, 16)}`);
    if (date.getMonth() !== month.getMonth() || date.getFullYear() !== month.getFullYear()) setMonth(monthStart(date));
  }

  function changeTime(nextHour: string, nextMinute: string) {
    setHour(nextHour);
    setMinute(nextMinute);
    if (validTimePart(nextHour, 24) && validTimePart(nextMinute, 60)) onChange(`${selectedKey}T${pad(Number(nextHour))}:${pad(Number(nextMinute))}`);
  }

  function onCalendarKey(event: KeyboardEvent<HTMLDivElement>) {
    const key = (event.target as HTMLElement).closest<HTMLButtonElement>('button[data-calendar-date]')?.dataset.calendarDate;
    if (!key) return;
    const current = parseDay(key);
    let next: Date;
    if (event.key === 'ArrowLeft') next = moveDay(current, -1);
    else if (event.key === 'ArrowRight') next = moveDay(current, 1);
    else if (event.key === 'ArrowUp') next = moveDay(current, -7);
    else if (event.key === 'ArrowDown') next = moveDay(current, 7);
    else if (event.key === 'Home') next = moveDay(current, -current.getDay());
    else if (event.key === 'End') next = moveDay(current, 6 - current.getDay());
    else if (event.key === 'PageUp' || event.key === 'PageDown') {
      const target = new Date(current.getFullYear(), current.getMonth() + (event.key === 'PageUp' ? -1 : 1), 1, 12);
      next = new Date(target.getFullYear(), target.getMonth(), Math.min(current.getDate(), new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate()), 12);
    } else return;
    event.preventDefault();
    chooseDay(next);
  }

  const popup = open && <div ref={panel} id={`${id}-dialog`} role="dialog" aria-label={`${label} 날짜와 시간`} style={{ ...position, colorScheme: theme }}
    className="fixed z-[1000] max-h-[calc(100dvh-16px)] w-[328px] max-w-[calc(100vw-16px)] overflow-y-auto rounded-[20px] border border-[var(--border)] bg-[var(--surface)] p-4 text-[var(--text)] shadow-[0_18px_60px_rgba(15,23,42,0.18)]">
    <div className="mb-2 flex items-center justify-between">
      <span className="flex items-center gap-2 text-xs font-bold text-[var(--primary)]"><MeetingFormIcon name="calendar" />{label} 날짜 · 시간</span>
      <button type="button" aria-label={`${label} 날짜 선택 닫기`} className={iconButton} onClick={() => close(true)}><MeetingFormIcon name="close" /></button>
    </div>
    <div className="mb-2 flex items-center gap-1">
      <button type="button" aria-label="이전 달" className={iconButton} onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1, 12))}><MeetingFormIcon name="chevron-left" /></button>
      <span className="flex-1 text-center text-sm font-bold" aria-live="polite">{month.getFullYear()}년 {month.getMonth() + 1}월</span>
      <button type="button" aria-label="다음 달" className={iconButton} onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1, 12))}><MeetingFormIcon name="chevron-right" /></button>
      <button type="button" className="ml-1 cursor-pointer rounded-lg border-0 bg-[var(--primary-soft)] px-2.5 py-1.5 text-[11px] font-bold text-[var(--primary)]" onClick={() => { chooseDay(today); setMonth(monthStart(today)); }}>오늘</button>
    </div>
    <div role="grid" aria-label={`${month.getFullYear()}년 ${month.getMonth() + 1}월 달력`} onKeyDown={onCalendarKey}>
      <div role="row" className="mb-1 grid grid-cols-7">
        {['일', '월', '화', '수', '목', '금', '토'].map((day, index) => <span role="columnheader" key={day} className={`py-2 text-center text-[11px] font-medium ${index === 0 ? 'text-[var(--danger)]' : 'text-[var(--muted)]'}`}>{day}</span>)}
      </div>
      {Array.from({ length: 6 }, (_, row) => <div role="row" key={row} className="grid grid-cols-7 gap-y-1">
        {days.slice(row * 7, row * 7 + 7).map((day) => {
          const key = dayKey(day);
          const active = selectedKey === key;
          const outsideMonth = day.getMonth() !== month.getMonth();
          return <div role="gridcell" aria-selected={active} key={key} className="flex items-center justify-center">
            <button type="button" data-calendar-date={key} aria-label={dayLabel(day)} aria-current={key === todayKey ? 'date' : undefined}
              tabIndex={active || (!selectedVisible && day.getDate() === 1 && !outsideMonth) ? 0 : -1} onClick={() => chooseDay(day)}
              className={`flex h-8 w-8 cursor-pointer items-center justify-center rounded-[10px] border-0 text-xs transition focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[var(--primary)] ${active ? 'bg-[var(--primary)] font-bold text-white shadow-sm' : `bg-transparent hover:bg-[var(--primary-soft)] ${outsideMonth ? 'text-[var(--muted)] opacity-40' : day.getDay() === 0 ? 'text-[var(--danger)]' : 'text-[var(--text)]'} ${key === todayKey ? 'ring-1 ring-[var(--primary)] ring-inset' : ''}`}`}>
              {day.getDate()}
            </button>
          </div>;
        })}
      </div>)}
    </div>
    <div className="mt-4 border-t border-[var(--border-soft)] pt-3">
      <div className="mb-2 flex items-center justify-between text-[11px]"><span className="flex items-center gap-1.5 font-semibold"><MeetingFormIcon name="clock" />시간</span><span className="text-[var(--muted)]">24시간제 · 한국 시간</span></div>
      <div className="flex items-center gap-2">
        <label className="flex min-w-0 flex-1 items-center rounded-xl border border-[var(--border)] bg-[var(--surface-2)] px-3 focus-within:border-[var(--primary)] focus-within:ring-2 focus-within:ring-[var(--primary-soft)]">
          <input aria-label={`${label} 시`} aria-invalid={!validTimePart(hour, 24) || undefined} className="min-w-0 w-full border-0 bg-transparent py-2.5 text-center text-sm font-semibold text-[var(--text)] outline-none" type="text" inputMode="numeric" maxLength={2} value={hour}
            onChange={(event) => changeTime(event.target.value.replace(/\D/g, ''), minute)} onBlur={() => { if (validTimePart(hour, 24)) setHour(pad(Number(hour))); }} />
          <span className="text-[11px] text-[var(--muted)]">시</span>
        </label>
        <span className="font-bold text-[var(--muted)]">:</span>
        <label className="flex min-w-0 flex-1 items-center rounded-xl border border-[var(--border)] bg-[var(--surface-2)] px-3 focus-within:border-[var(--primary)] focus-within:ring-2 focus-within:ring-[var(--primary-soft)]">
          <input aria-label={`${label} 분`} aria-invalid={!validTimePart(minute, 60) || undefined} className="min-w-0 w-full border-0 bg-transparent py-2.5 text-center text-sm font-semibold text-[var(--text)] outline-none" type="text" inputMode="numeric" maxLength={2} value={minute}
            onChange={(event) => changeTime(hour, event.target.value.replace(/\D/g, ''))} onBlur={() => { if (validTimePart(minute, 60)) setMinute(pad(Number(minute))); }} />
          <span className="text-[11px] text-[var(--muted)]">분</span>
        </label>
      </div>
      {!timeValid && <p className="m-0 mt-2 text-[11px] text-[var(--danger)]" role="alert">시는 00~23, 분은 00~59로 입력해 주세요.</p>}
      <button type="button" disabled={!timeValid} onClick={() => { changeTime(pad(Number(hour)), pad(Number(minute))); close(true); }}
        className="mt-3 h-10 w-full cursor-pointer rounded-xl border-0 bg-[var(--primary)] text-xs font-bold text-white transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-40">선택 완료</button>
    </div>
  </div>;

  return <>
    <button ref={trigger} type="button" disabled={disabled} aria-label={`${label} 날짜와 시간 선택`} aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? `${id}-dialog` : undefined} aria-invalid={invalid || undefined} onClick={() => open ? close(true) : show()}
      className={`mt-2 flex min-w-0 w-full cursor-pointer items-center gap-2.5 rounded-xl border bg-[var(--surface)] px-3 py-2.5 text-left transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--primary)] disabled:cursor-not-allowed disabled:opacity-50 ${invalid ? 'border-[var(--danger)]' : open ? 'border-[var(--primary)] ring-2 ring-[var(--primary-soft)]' : 'border-[var(--border)] hover:border-[var(--primary)]'}`}>
      <MeetingFormIcon name="calendar" className="h-4 w-4 text-[var(--primary)]" />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-xs font-semibold text-[var(--text)]">{selected.getFullYear()}.{pad(selected.getMonth() + 1)}.{pad(selected.getDate())} <span className="font-normal text-[var(--muted)]">({['일', '월', '화', '수', '목', '금', '토'][selected.getDay()]})</span></span>
        <span className="mt-0.5 block text-[11px] font-medium text-[var(--muted)]">{value.slice(11, 16)}</span>
      </span>
      <MeetingFormIcon name="chevron-right" className={`h-3.5 w-3.5 text-[var(--muted)] transition-transform ${open ? '-rotate-90' : 'rotate-90'}`} />
    </button>
    {popup && createPortal(popup, document.body)}
  </>;
}
