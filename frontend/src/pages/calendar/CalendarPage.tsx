// UI-07 일정 (Google Calendar) (담당: A) — 팀원 UI 시안(togather_ui.html): 달력 카드 | 선택한 날·일정 패널
import { useMemo, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { createEvent, deleteEvent, getEvents, updateEvent, type CalendarEvent, type CalendarEventForm } from './api';
import { getMembers, type MemberResponse } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { useWorkspaceEvents } from '../../hooks/useWorkspaceEvents';
import { ApiError } from '../../api/client';
import { ErrorBox } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import ServiceLogo from '../../components/ServiceLogo';
import NavIcon from '../../layouts/NavIcon';
import {
  addDays,
  formatDate,
  formatTime,
  fromLocalIso,
  sameDay,
  startOfDay,
  toInputValue,
  toLocalIso,
} from '../../utils/date';

type View = 'month' | 'week';
type Filter = 'ALL' | 'TEAM' | 'PERSONAL';
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

function visibleRange(view: View, anchor: Date): { start: Date; days: number } {
  if (view === 'week') {
    const start = startOfDay(addDays(anchor, -anchor.getDay()));
    return { start, days: 7 };
  }
  const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
  return { start: addDays(first, -first.getDay()), days: 42 };
}

function dayTitle(d: Date) {
  return `${d.getMonth() + 1}월 ${d.getDate()}일 ${WEEKDAYS[d.getDay()]}요일`;
}

function timeRange(e: CalendarEvent) {
  return e.allDay ? '종일' : `${formatTime(e.startAt)} - ${formatTime(e.endAt)}`;
}

export default function CalendarPage() {
  const { workspaceId = '' } = useParams();
  const [view, setView] = useState<View>('month');
  const [filter, setFilter] = useState<Filter>('ALL');
  const [anchor, setAnchor] = useState(() => startOfDay(new Date()));
  const [selectedDay, setSelectedDay] = useState(() => startOfDay(new Date()));
  const { start, days } = visibleRange(view, anchor);
  const from = toLocalIso(start);
  const to = toLocalIso(addDays(start, days));
  const events = useAsync(() => getEvents(workspaceId, from, to), [workspaceId, from, to]);
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [editing, setEditing] = useState<CalendarEvent | 'new' | null>(null);

  // 다른 팀원이 팀 캘린더 일정을 바꾸면 새로고침 없이 다시 불러온다.
  useWorkspaceEvents(workspaceId, (event) => {
    if (event.provider === 'GOOGLE_CALENDAR') events.reload();
  });

  const move = (dir: number) =>
    setAnchor(view === 'week' ? addDays(anchor, dir * 7) : new Date(anchor.getFullYear(), anchor.getMonth() + dir, 1));

  const shown = (events.data ?? []).filter(
    (e) => filter === 'ALL' || (filter === 'PERSONAL' ? e.personal : !e.personal),
  );

  const byDay = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>();
    for (const e of shown) {
      const s = startOfDay(fromLocalIso(e.startAt));
      const end = fromLocalIso(e.endAt);
      for (let d = s; d < end || sameDay(d, s); d = addDays(d, 1)) {
        const key = d.toDateString();
        map.set(key, [...(map.get(key) ?? []), e]);
        if (e.allDay && addDays(d, 1) >= end) break;
      }
    }
    return map;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [events.data, filter]);

  const dayEvents = byDay.get(selectedDay.toDateString()) ?? [];
  const selected = (events.data ?? []).find((e) => e.contentRefId === selectedId) ?? dayEvents[0] ?? null;

  const notConnected = events.error instanceof ApiError && events.error.code === 'CONNECTION_NOT_FOUND';
  const title =
    view === 'month'
      ? `${anchor.getFullYear()}년 ${anchor.getMonth() + 1}월`
      : `${formatDate(start)} ~ ${formatDate(addDays(start, 6))}`;

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="calendar" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="일정"
        description="팀의 회의와 내 일정을 한눈에 확인하세요."
        actions={
          <>
            {!notConnected && events.data && <span className="badge badge-success">● Google Calendar 연결됨</span>}
            <button type="button" className="btn" onClick={events.reload} disabled={events.loading}>
              {events.loading ? '불러오는 중...' : '↻ 동기화'}
            </button>
            <button type="button" className="btn btn-primary" onClick={() => setEditing('new')} disabled={notConnected}>
              + 새 일정
            </button>
          </>
        }
      />

      {notConnected ? (
        <div className="alert">
          Google Calendar가 연결되지 않았습니다. <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리</Link>
          에서 연결하세요.
        </div>
      ) : (
        events.error && <ErrorBox error={events.error} onRetry={events.reload} />
      )}

      <div className="cal-board">
        <div className="card cal-card">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <button type="button" className="btn btn-small" onClick={() => move(-1)} aria-label="이전">
                ‹
              </button>
              <strong className="min-w-28 text-center text-xl font-extrabold">{title}</strong>
              <button type="button" className="btn btn-small" onClick={() => move(1)} aria-label="다음">
                ›
              </button>
              <button
                type="button"
                className="btn btn-small"
                onClick={() => {
                  const today = startOfDay(new Date());
                  setAnchor(today);
                  setSelectedDay(today);
                  setSelectedId(null);
                }}
              >
                오늘
              </button>
            </div>
            <div className="flex flex-wrap gap-2">
              <div className="segmented">
                {(
                  [
                    ['ALL', '전체'],
                    ['TEAM', '● 팀 일정'],
                    ['PERSONAL', '● 개인 일정'],
                  ] as const
                ).map(([v, label]) => (
                  <button
                    key={v}
                    type="button"
                    className={`${filter === v ? 'active' : ''} cal-filter-${v.toLowerCase()}`}
                    onClick={() => setFilter(v)}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <div className="segmented">
                {(['month', 'week'] as const).map((v) => (
                  <button key={v} type="button" className={view === v ? 'active' : ''} onClick={() => setView(v)}>
                    {v === 'month' ? '월' : '주'}
                  </button>
                ))}
              </div>
            </div>
          </div>

          <div className={`cal-grid cal-${view}`}>
            {WEEKDAYS.map((d, i) => (
              <div key={d} className={`cal-head${i === 0 ? ' sun' : i === 6 ? ' sat' : ''}`}>
                {d}
              </div>
            ))}
            {Array.from({ length: days }, (_, i) => addDays(start, i)).map((day) => {
              const list = byDay.get(day.toDateString()) ?? [];
              const outside = view === 'month' && day.getMonth() !== anchor.getMonth();
              const isSelected = sameDay(day, selectedDay);
              const dow = day.getDay();
              return (
                <div
                  key={day.toISOString()}
                  className={`cal-cell${outside ? ' outside' : ''}${isSelected ? ' selected' : ''}${
                    sameDay(day, new Date()) ? ' today' : ''
                  }${dow === 0 ? ' sun' : dow === 6 ? ' sat' : ''}`}
                  onClick={() => {
                    setSelectedDay(startOfDay(day));
                    setSelectedId(null);
                  }}
                >
                  <span className="cal-date">{day.getDate()}</span>
                  {list.slice(0, 3).map((e) => (
                    <button
                      key={`${e.contentRefId}-${day.getDate()}`}
                      type="button"
                      className={`cal-chip ${e.personal ? 'personal' : 'team'}${
                        selected?.contentRefId === e.contentRefId && isSelected ? ' active' : ''
                      }`}
                      onClick={(ev) => {
                        ev.stopPropagation();
                        setSelectedDay(startOfDay(day));
                        setSelectedId(e.contentRefId);
                      }}
                      title={e.title ?? ''}
                    >
                      ● {!e.allDay && `${formatTime(e.startAt)} `}
                      {e.title ?? '(제목 없음)'}
                    </button>
                  ))}
                  {list.length > 3 && <span className="text-[11px] text-[var(--muted)]">+{list.length - 3}개 더</span>}
                </div>
              );
            })}
          </div>
        </div>

        <div className="card cal-side">
          <div className="flex items-center justify-between gap-2">
            <strong className="text-xl font-extrabold">{dayTitle(selectedDay)}</strong>
            <span className="badge badge-primary">일정 {dayEvents.length}개</span>
          </div>
          {dayEvents.length === 0 ? (
            <div className="text-[13px] text-[var(--muted)]">이 날은 일정이 없어요.</div>
          ) : (
            <div className="flex flex-col gap-2">
              {dayEvents.map((e) => (
                <button
                  key={e.contentRefId}
                  type="button"
                  className={`cal-day-item${selected?.contentRefId === e.contentRefId ? ' active' : ''}`}
                  onClick={() => setSelectedId(e.contentRefId)}
                >
                  <span className={`cal-dot ${e.personal ? 'personal' : 'team'}`} />
                  <strong className="whitespace-nowrap">{timeRange(e)}</strong>
                  <span className="truncate">{e.title ?? '(제목 없음)'}</span>
                </button>
              ))}
            </div>
          )}

          {selected && (
            <EventPanel
              key={selected.contentRefId}
              workspaceId={workspaceId}
              event={selected}
              members={members.data ?? []}
              onEdit={() => setEditing(selected)}
              onDeleted={() => {
                setSelectedId(null);
                events.reload();
              }}
            />
          )}
        </div>
      </div>

      {editing && (
        <EventForm
          workspaceId={workspaceId}
          event={editing === 'new' ? null : editing}
          members={members.data ?? []}
          defaultDate={selectedDay}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            events.reload();
          }}
        />
      )}
    </section>
  );
}

/** 오른쪽 아래: 선택한 일정 상세 */
function EventPanel({
  workspaceId,
  event,
  members,
  onEdit,
  onDeleted,
}: {
  workspaceId: string;
  event: CalendarEvent;
  members: MemberResponse[];
  onEdit: () => void;
  onDeleted: () => void;
}) {
  const [error, setError] = useState<Error>();
  const names = event.attendees.map((email) => members.find((m) => m.email === email)?.name ?? email);

  const remove = async () => {
    if (!window.confirm('이 일정을 삭제할까요? Google Calendar에서도 삭제됩니다.')) return;
    try {
      await deleteEvent(workspaceId, event.contentRefId);
      onDeleted();
    } catch (err) {
      setError(err as Error);
    }
  };

  return (
    <div className="flex flex-col gap-3 border-t border-[var(--border-soft)] pt-4">
      <div className="flex items-center justify-between">
        <span className="text-[13px] font-semibold text-[var(--muted)]">선택한 일정</span>
        <div className="flex gap-1.5">
          <button type="button" className="btn btn-small" onClick={onEdit}>
            수정
          </button>
          <button type="button" className="btn btn-small btn-danger" onClick={remove}>
            삭제
          </button>
        </div>
      </div>
      <strong className="text-xl font-extrabold">{event.title ?? '(제목 없음)'}</strong>
      <div className="flex flex-wrap gap-1.5">
        <span className={`badge ${event.personal ? 'badge-info' : 'badge-primary'}`}>
          {event.personal ? '개인 일정' : event.meetingId ? '팀 회의' : '팀 일정'}
        </span>
        {event.meetingId && <span className="badge badge-success">✓ 확정</span>}
      </div>
      <div className="flex flex-col gap-1.5 text-sm">
        <span>📅 {formatDate(event.startAt)}</span>
        <span>🕓 {timeRange(event)}</span>
        {event.meetLink && (
          <span>
            📹 온라인 ·{' '}
            <a href={event.meetLink} target="_blank" rel="noreferrer">
              Google Meet 참여
            </a>
          </span>
        )}
        {names.length > 0 && (
          <span className="flex flex-wrap items-center gap-1.5">
            {names.slice(0, 5).map((n) => (
              <span key={n} className="tg-avatar">
                {n.slice(0, 1)}
              </span>
            ))}
            <span className="text-[13px] text-[var(--muted)]">{names.join(' · ')}</span>
          </span>
        )}
      </div>
      {event.description && <p className="pre m-0 text-[13px] text-[var(--muted)]">{event.description}</p>}
      <div className="flex flex-wrap gap-3 text-[13px] font-semibold">
        {event.deepLink && (
          <a href={event.deepLink} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1">
            <span className="inline-flex h-4 w-4">
              <ServiceLogo service="gcal" />
            </span>
            Google Calendar에서 열기
          </a>
        )}
        {event.meetingId && <Link to={`/workspaces/${workspaceId}/meetings/${event.meetingId}`}>회의 보기 →</Link>}
      </div>
      {error && <ErrorBox error={error} />}
    </div>
  );
}

function EventForm({
  workspaceId,
  event,
  members,
  defaultDate,
  onClose,
  onSaved,
}: {
  workspaceId: string;
  event: CalendarEvent | null;
  members: MemberResponse[];
  defaultDate: Date;
  onClose: () => void;
  onSaved: () => void;
}) {
  const initialStart = new Date(defaultDate);
  initialStart.setHours(10, 0, 0, 0);
  const [form, setForm] = useState({
    title: event?.title ?? '',
    startAt: event ? event.startAt.slice(0, 16) : toInputValue(initialStart),
    endAt: event ? event.endAt.slice(0, 16) : toInputValue(new Date(initialStart.getTime() + 3600_000)),
    participantIds: event
      ? members.filter((m) => event.attendees.includes(m.email)).map((m) => m.userId)
      : ([] as number[]),
    description: event?.description ?? '',
  });
  const [error, setError] = useState<Error>();
  const [saving, setSaving] = useState(false);

  const toggleMember = (id: number) =>
    setForm({
      ...form,
      participantIds: form.participantIds.includes(id)
        ? form.participantIds.filter((x) => x !== id)
        : [...form.participantIds, id],
    });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (form.startAt >= form.endAt) {
      setError(new Error('종료 시각은 시작 시각보다 뒤여야 합니다.'));
      return;
    }
    setSaving(true);
    setError(undefined);
    const body: CalendarEventForm = { ...form, startAt: `${form.startAt}:00`, endAt: `${form.endAt}:00` };
    try {
      if (event) await updateEvent(workspaceId, event.contentRefId, body);
      else await createEvent(workspaceId, body);
      onSaved();
    } catch (err) {
      setError(err as Error);
      setSaving(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="modal form" onSubmit={submit} onClick={(e) => e.stopPropagation()}>
        <h2>{event ? '일정 수정' : '일정 등록'}</h2>
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
            시작 *
            <input
              className="input"
              type="datetime-local"
              required
              value={form.startAt}
              onChange={(e) => setForm({ ...form, startAt: e.target.value })}
            />
          </label>
          <label className="grow">
            종료 *
            <input
              className="input"
              type="datetime-local"
              required
              value={form.endAt}
              onChange={(e) => setForm({ ...form, endAt: e.target.value })}
            />
          </label>
        </div>
        <fieldset>
          <legend>참여자</legend>
          <div className="tags">
            {members.map((m) => (
              <label key={m.userId} className="tag selectable">
                <input
                  type="checkbox"
                  checked={form.participantIds.includes(m.userId)}
                  onChange={() => toggleMember(m.userId)}
                />
                {m.name}
              </label>
            ))}
          </div>
        </fieldset>
        <label>
          설명
          <textarea
            className="input"
            maxLength={5000}
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        </label>
        {error && <ErrorBox error={error} />}
        <div className="row">
          <button className="btn btn-primary" disabled={saving}>
            {saving ? '저장 중...' : '저장'}
          </button>
          <button type="button" className="btn" onClick={onClose}>
            취소
          </button>
        </div>
      </form>
    </div>
  );
}

