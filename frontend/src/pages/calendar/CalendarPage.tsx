// UI-07 일정 (Google Calendar) (담당: A)
import { useMemo, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { createEvent, deleteEvent, getEvents, updateEvent, type CalendarEvent, type CalendarEventForm } from './api';
import { getMembers, type MemberResponse } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { useWorkspaceEvents } from '../../hooks/useWorkspaceEvents';
import { ApiError } from '../../api/client';
import { ErrorBox } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import { BTN_AI, BTN_SUB, MUTED, SUB_CARD } from '../../components/ui';
import {
  addDays,
  formatDate,
  formatDateTime,
  formatTime,
  fromLocalIso,
  sameDay,
  startOfDay,
  toInputValue,
  toLocalIso,
} from '../../utils/date';

type View = 'month' | 'week';
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

function visibleRange(view: View, anchor: Date): { start: Date; days: number } {
  if (view === 'week') {
    const start = startOfDay(addDays(anchor, -anchor.getDay()));
    return { start, days: 7 };
  }
  const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
  return { start: addDays(first, -first.getDay()), days: 42 };
}

export default function CalendarPage() {
  const { workspaceId = '' } = useParams();
  const [view, setView] = useState<View>('month');
  const [anchor, setAnchor] = useState(() => startOfDay(new Date()));
  const { start, days } = visibleRange(view, anchor);
  const from = toLocalIso(start);
  const to = toLocalIso(addDays(start, days));
  const events = useAsync(() => getEvents(workspaceId, from, to), [workspaceId, from, to]);
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const [selected, setSelected] = useState<CalendarEvent | null>(null);
  const [editing, setEditing] = useState<CalendarEvent | 'new' | null>(null);

  // 다른 팀원이 팀 캘린더 일정을 바꾸면 새로고침 없이 다시 불러온다.
  useWorkspaceEvents(workspaceId, (event) => {
    if (event.provider === 'GOOGLE_CALENDAR') events.reload();
  });

  const move = (dir: number) =>
    setAnchor(view === 'week' ? addDays(anchor, dir * 7) : new Date(anchor.getFullYear(), anchor.getMonth() + dir, 1));

  const byDay = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>();
    for (const e of events.data ?? []) {
      const s = startOfDay(fromLocalIso(e.startAt));
      const end = fromLocalIso(e.endAt);
      for (let d = s; d < end || sameDay(d, s); d = addDays(d, 1)) {
        const key = d.toDateString();
        map.set(key, [...(map.get(key) ?? []), e]);
        if (e.allDay && addDays(d, 1) >= end) break;
      }
    }
    return map;
  }, [events.data]);

  const notConnected = events.error instanceof ApiError && events.error.code === 'CONNECTION_NOT_FOUND';
  const title =
    view === 'month'
      ? `${anchor.getFullYear()}년 ${anchor.getMonth() + 1}월`
      : `${formatDate(start)} ~ ${formatDate(addDays(start, 6))}`;

  return (
    <section className="stack !gap-5">
      <PageHeader
        eyebrow="GOOGLE CALENDAR"
        title="일정"
        description="팀 캘린더 일정을 보고 등록합니다. 확정된 회의는 따로 표시됩니다."
        actions={
          <>
            <div className={`${SUB_CARD} inline-flex gap-1 p-1`}>
              {(['month', 'week'] as const).map((v) => (
                <button
                  key={v}
                  onClick={() => setView(v)}
                  className={`cursor-pointer rounded-xl border-0 px-4 py-1.5 text-xs font-bold transition ${
                    view === v ? 'bg-indigo-600 text-white' : `bg-transparent ${MUTED}`
                  }`}
                >
                  {v === 'month' ? '월' : '주'}
                </button>
              ))}
            </div>
            <button className={BTN_AI} onClick={() => setEditing('new')} disabled={notConnected}>
              + 일정
            </button>
          </>
        }
      />

      <div className="flex items-center gap-2">
        <button className={BTN_SUB} onClick={() => move(-1)} aria-label="이전">
          ◀
        </button>
        <strong className="min-w-32 text-center text-lg">{title}</strong>
        <button className={BTN_SUB} onClick={() => move(1)} aria-label="다음">
          ▶
        </button>
        <button className={BTN_SUB} onClick={() => setAnchor(startOfDay(new Date()))}>
          오늘
        </button>
        {events.loading && <span className={`text-xs ${MUTED}`}>불러오는 중...</span>}
      </div>

      {notConnected ? (
        <div className="alert">
          Google Calendar가 연결되지 않았습니다. <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리</Link>
          에서 연결하세요.
        </div>
      ) : (
        events.error && <ErrorBox error={events.error} onRetry={events.reload} />
      )}

      <div className={`calendar calendar-${view}`}>
        {WEEKDAYS.map((d) => (
          <div key={d} className="calendar-head">
            {d}
          </div>
        ))}
        {Array.from({ length: days }, (_, i) => addDays(start, i)).map((day) => {
          const dayEvents = byDay.get(day.toDateString()) ?? [];
          const outside = view === 'month' && day.getMonth() !== anchor.getMonth();
          return (
            <div
              key={day.toISOString()}
              className={`calendar-cell ${outside ? 'outside' : ''} ${sameDay(day, new Date()) ? 'today' : ''}`}
            >
              <span className="calendar-date">{day.getDate()}</span>
              {dayEvents.map((e) => (
                <button
                  key={`${e.contentRefId}-${day.getDate()}`}
                  className={`calendar-event ${e.meetingId ? 'meeting' : ''}`}
                  onClick={() => setSelected(e)}
                  title={e.title ?? ''}
                >
                  {!e.allDay && <span className="time">{formatTime(e.startAt)}</span>} {e.title ?? '(제목 없음)'}
                </button>
              ))}
            </div>
          );
        })}
      </div>
      <p className="muted small">
        <span className="legend meeting" /> ToGather에서 확정한 회의
      </p>

      {selected && (
        <EventDetail
          workspaceId={workspaceId}
          event={selected}
          members={members.data ?? []}
          onClose={() => setSelected(null)}
          onEdit={() => {
            setEditing(selected);
            setSelected(null);
          }}
          onDeleted={() => {
            setSelected(null);
            events.reload();
          }}
        />
      )}
      {editing && (
        <EventForm
          workspaceId={workspaceId}
          event={editing === 'new' ? null : editing}
          members={members.data ?? []}
          defaultDate={anchor}
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

function EventDetail({
  workspaceId,
  event,
  members,
  onClose,
  onEdit,
  onDeleted,
}: {
  workspaceId: string;
  event: CalendarEvent;
  members: MemberResponse[];
  onClose: () => void;
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
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal stack" onClick={(e) => e.stopPropagation()}>
        <div className="row">
          <h2>{event.title ?? '(제목 없음)'}</h2>
          {event.meetingId && <span className="badge badge-primary">회의</span>}
          {event.personal && <span className="badge">내 캘린더</span>}
        </div>
        <p>
          {event.allDay
            ? `${formatDate(event.startAt)} 종일`
            : `${formatDateTime(event.startAt)} ~ ${formatTime(event.endAt)}`}
        </p>
        {names.length > 0 && <p className="small">참여자: {names.join(', ')}</p>}
        {event.description && <p className="pre">{event.description}</p>}
        <div className="row">
          {event.meetLink && (
            <a className="btn btn-small" href={event.meetLink} target="_blank" rel="noreferrer">
              화상 회의 참여
            </a>
          )}
          {event.deepLink && (
            <a className="btn btn-small" href={event.deepLink} target="_blank" rel="noreferrer">
              Google Calendar에서 열기
            </a>
          )}
          {event.meetingId && (
            <Link className="btn btn-small" to={`/workspaces/${workspaceId}/meetings/${event.meetingId}`}>
              회의 보기
            </Link>
          )}
        </div>
        {error && <ErrorBox error={error} />}
        <div className="row">
          <button className="btn" onClick={onEdit}>
            수정
          </button>
          <button className="btn btn-danger" onClick={remove}>
            삭제
          </button>
          <span className="spacer" />
          <button className="btn" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
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

