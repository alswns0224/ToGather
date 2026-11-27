// UI-10 회의 상세 · 안건 · 가용 시간 · 추천 · 확정 (담당: A)
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useWorkspaceEvents } from '../../hooks/useWorkspaceEvents';
import { Link, useParams } from 'react-router';
import {
  addAgenda,
  addTextAgenda,
  cancelMeeting,
  confirmMeeting,
  getConflicts,
  deleteAgenda,
  getAgendaCandidates,
  getMeeting,
  getMyAvailability,
  recommendTimes,
  retryCalendar,
  saveAvailability,
  type AvailabilityResponse,
  type MeetingDetailResponse,
  type SlotPreference,
  type TimeCandidate,
} from './api';
import { MEETING_BADGE, STATUS_TEXT } from './MeetingListPage';
import MeetingEditForm from './MeetingEditForm';
import MeetingFormIcon from './MeetingFormIcon';
import './MeetingAvailabilityGrid.css';
import MeetingAgendaCard from './MeetingAgendaCard';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';
import { MUTED } from '../../components/ui';
import PageHeader from '../../components/PageHeader';
import NavIcon from '../../layouts/NavIcon';
import { addDays, formatDate, formatDateTime, formatTime, fromLocalIso, startOfDay, toLocalIso } from '../../utils/date';

export default function MeetingDetailPage() {
  const { workspaceId = '', meetingId = '' } = useParams();
  const meeting = useAsync(() => getMeeting(workspaceId, meetingId), [workspaceId, meetingId]);
  // 다른 참여자가 가능 시간을 저장하거나 회의를 바꾸면 응답 수·상태를 새로고침 없이 다시 불러온다.
  useWorkspaceEvents(workspaceId, (event) => {
    if (event.provider === 'MEETING' && event.targetId === meetingId) meeting.reload();
  });
  const [actionError, setActionError] = useState<Error>();
  const [editing, setEditing] = useState(false);
  const [acting, setActing] = useState(false);
  const m = meeting.data;

  const run = async (fn: () => Promise<MeetingDetailResponse | void>) => {
    if (acting) return;
    setActing(true);
    setActionError(undefined);
    try {
      const result = await fn();
      if (result) meeting.setData(result);
      else meeting.reload();
    } catch (err) {
      setActionError(err as Error);
    } finally {
      setActing(false);
    }
  };

  const header = (
    <PageHeader
      icon={<NavIcon name="meetings" className="h-[22px] w-[22px] text-[var(--primary)]" />}
      title="회의"
      description="안건을 모으고, 팀이 함께할 시간을 정해요."
      actions={
        <Link to={`/workspaces/${workspaceId}/meetings`} className="btn">
          ← 회의 목록
        </Link>
      }
    />
  );

  if (meeting.loading && !m) return <Loading />;
  if (meeting.error) return <ErrorBox error={meeting.error} onRetry={meeting.reload} />;
  if (!m) return null;

  const scheduling = m.status === 'SCHEDULING';
  const responded = m.participants.filter((p) => p.hasResponded).length;

  return (
    <section className="stack">
      {header}
      {actionError && <ErrorBox error={actionError} />}
      {editing && (
        <MeetingEditForm
          workspaceId={workspaceId}
          meeting={m}
          onClose={() => setEditing(false)}
          onSaved={(updated) => {
            meeting.setData(updated);
            setEditing(false);
          }}
        />
      )}

      <div className={scheduling ? 'meeting-board' : 'flex flex-col gap-4'}>
        <div className="card meeting-main min-w-0">
          {/* 머리: 아이콘 · 제목 · 상태 · 요약 */}
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="flex min-w-0 items-center gap-3">
              <span className="meeting-icon">
                <NavIcon name="calendar" />
              </span>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="!m-0 !text-[19px] !font-extrabold">{m.title}</h2>
                  <span className={`badge ${MEETING_BADGE[m.status]}`}>{STATUS_TEXT[m.status]}</span>
                </div>
                <div className="mt-0.5 text-[13px] text-[var(--muted)]">
                  {m.durationMinutes >= 60 ? `${m.durationMinutes / 60}시간` : `${m.durationMinutes}분`} ·{' '}
                  {m.meetingMode ? `${m.meetingMode} 회의` : '진행 방식 미정'} · 팀원 {m.participants.length}명
                  {scheduling && ` · 후보 기간 ${formatDateTime(m.candidateStartAt)} ~ ${formatDateTime(m.candidateEndAt)}`}
                </div>
              </div>
            </div>
            <div className="flex gap-2">
              {m.canManage && m.status !== 'CANCELED' && !editing && (
                <button type="button" className="btn btn-small" disabled={acting} onClick={() => setEditing(true)}>
                  수정
                </button>
              )}
              {m.canManage && m.status !== 'CANCELED' && (
                <button
                  type="button"
                  className="btn btn-small btn-danger"
                  disabled={acting || editing}
                  onClick={() =>
                    window.confirm('회의를 취소할까요? 확정된 회의면 캘린더 일정도 삭제됩니다.') &&
                    run(() => cancelMeeting(workspaceId, m.meetingId))
                  }
                >
                  회의 취소
                </button>
              )}
            </div>
          </div>

          {m.status === 'CONFIRMED' && m.startAt && (
            <div className="meeting-confirmed">
              <div className="text-xs font-bold text-[var(--success)]">✓ 확정된 회의</div>
              <strong className="text-lg">
                {formatDateTime(m.startAt)} ~ {m.endAt && formatTime(m.endAt)}
              </strong>
              <div className="flex flex-wrap items-center gap-3 text-[13px]">
                {m.meetLink && (
                  <a className="btn btn-primary btn-small" href={m.meetLink} target="_blank" rel="noreferrer">
                    화상 회의 참여
                  </a>
                )}
                {m.calendarRefId && <Link to={`/workspaces/${workspaceId}/calendar`}>캘린더에서 보기</Link>}
              </div>
              {m.calendarSyncFailed && (
                <div className="alert alert-error">
                  회의는 저장됐지만 Google Calendar에 최신 내용을 반영하지 못했습니다. 연동 상태를 확인한 뒤 다시 시도하세요.
                  <span className="block text-xs">기존 일정은 원래 캘린더를 다시 연결해 주세요. 개인 일정은 그 일정의 소유자가 재시도할 수 있어요.</span>
                  {m.canManage && (
                    <button className="btn btn-small" disabled={acting || editing} onClick={() =>
                      window.confirm('Calendar에 최신 회의 정보를 다시 반영할까요? 원본 일정이 삭제됐다면 새 일정으로 등록합니다.') &&
                      run(() => retryCalendar(workspaceId, m.meetingId))}>
                      {acting ? '반영 중...' : '다시 반영'}
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          <Agendas workspaceId={workspaceId} meeting={m} busy={acting} onChange={run} />

          {scheduling && (
            <div className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center gap-2">
                <strong className="text-sm">팀원 참여 가능 시간</strong>
                <span className={`badge ${responded === m.participants.length ? 'badge-success' : ''}`}>
                  {responded === m.participants.length ? '✓ ' : ''}
                  {responded}/{m.participants.length}명 응답
                </span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {m.participants.map((p) => (
                  <span key={p.userId} className={`badge ${p.hasResponded ? 'badge-success' : ''}`}>
                    {p.hasResponded ? '✓' : '…'} {p.name}
                  </span>
                ))}
              </div>
              {m.participating ? (
                <AvailabilityGrid
                  key={`grid-${m.candidateStartAt}-${m.candidateEndAt}-${m.durationMinutes}`}
                  workspaceId={workspaceId}
                  meetingId={m.meetingId}
                  onSaved={meeting.reload}
                />
              ) : (
                <p className={`m-0 text-sm ${MUTED}`}>회의 참여자만 가용 시간을 입력할 수 있습니다.</p>
              )}
            </div>
          )}
        </div>

        {scheduling && (
          <Recommendations
            key={`rec-${m.candidateStartAt}-${m.candidateEndAt}-${m.durationMinutes}-${responded}`}
            workspaceId={workspaceId}
            meeting={m}
            onConfirm={(startAt, force) => run(() => confirmMeeting(workspaceId, m.meetingId, startAt, force))}
          />
        )}
      </div>
    </section>
  );
}

function Agendas({
  workspaceId,
  meeting,
  busy,
  onChange,
}: {
  workspaceId: string;
  meeting: MeetingDetailResponse;
  busy: boolean;
  onChange: (fn: () => Promise<MeetingDetailResponse>) => void;
}) {
  const editable = meeting.canManage && meeting.status !== 'CANCELED';
  const [adding, setAdding] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const candidates = useAsync(
    () => (adding ? getAgendaCandidates(workspaceId) : Promise.resolve([])),
    [workspaceId, adding],
  );
  const used = new Set(meeting.agendas.map((a) => a.taskId));

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-1.5">
        <strong className="text-sm">회의 안건</strong>
        <span className="badge">{meeting.agendas.length}</span>
        <span className="flex-1" />
        {editable && (
          <button type="button" className="analysis-ghost" disabled={busy} onClick={() => setAdding(!adding)}>
            {adding ? '닫기' : '+ 안건 추가'}
          </button>
        )}
      </div>
      {meeting.agendas.length === 0 && <span className="text-[13px] text-[var(--muted)]">안건이 없습니다.</span>}
      {meeting.agendas.map((agenda) => (
        <MeetingAgendaCard key={`${workspaceId}/${meeting.meetingId}/${agenda.agendaId}/${agenda.taskId}`}
          workspaceId={workspaceId} agenda={agenda} editable={editable}
          onRemove={() => onChange(() => deleteAgenda(workspaceId, meeting.meetingId, agenda.agendaId))} />
      ))}
      {adding && (
        <div className="rounded-xl border border-[var(--border)] bg-[var(--surface-2)] p-4">
          <form className="mb-4" onSubmit={(event) => {
            event.preventDefault();
            const title = newTitle.trim();
            if (!title || busy) return;
            onChange(async () => {
              const updated = await addTextAgenda(workspaceId, meeting.meetingId, title);
              setNewTitle('');
              return updated;
            });
          }}>
            <label htmlFor={`meeting-${meeting.meetingId}-agenda-title`} className="mb-2 block text-xs font-semibold">새 안건 직접 입력</label>
            <div className="flex flex-wrap gap-2">
              <input id={`meeting-${meeting.meetingId}-agenda-title`} className="min-w-0 flex-1 rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3 py-2.5 text-sm text-[var(--text)] outline-none placeholder:text-[var(--muted)] focus:border-[var(--primary)]"
                required maxLength={200} disabled={busy} placeholder="예: 시연 순서와 역할 분담" value={newTitle} onChange={(event) => setNewTitle(event.target.value)} />
              <button type="submit" disabled={busy || !newTitle.trim()} className="btn btn-primary">{busy ? '추가 중…' : '안건 추가'}</button>
            </div>
          </form>
          <p className="m-0 mb-2 border-t border-[var(--border)] pt-3 text-xs font-semibold">기존 팀 작업 연결</p>
          <div className="tags">
          {candidates.loading && <Loading />}
          {candidates.error && <ErrorBox error={candidates.error} onRetry={candidates.reload} />}
          {candidates.data
            ?.filter((t) => !used.has(t.taskId))
            .map((t) => (
              <button
                key={t.taskId}
                className="tag selectable"
                disabled={busy}
                onClick={() => onChange(() => addAgenda(workspaceId, meeting.meetingId, t.taskId))}
              >
                + {t.title}
              </button>
            ))}
          {candidates.data && candidates.data.filter((t) => !used.has(t.taskId)).length === 0 && (
            <span className="muted small">추가할 수 있는 진행 중 팀 작업이 없습니다.</span>
          )}
          </div>
        </div>
      )}
    </div>
  );
}

// ===================== 가용 시간 입력 표 =====================

/** 칸을 누를 때마다 가능 → 비선호 → 불가능 순서로 바뀐다. 칠하지 않은 칸은 불가능으로 본다. */
const NEXT: Record<SlotPreference, SlotPreference> = {
  UNAVAILABLE: 'AVAILABLE',
  AVAILABLE: 'NOT_PREFERRED',
  NOT_PREFERRED: 'UNAVAILABLE',
};
const PREF_TEXT: Record<SlotPreference, string> = { AVAILABLE: '가능', NOT_PREFERRED: '비선호', UNAVAILABLE: '불가능' };
const slotKey = (value: string | Date) => toLocalIso(typeof value === 'string' ? fromLocalIso(value) : value);

function AvailabilityGrid({
  workspaceId,
  meetingId,
  onSaved,
}: {
  workspaceId: string;
  meetingId: number;
  onSaved: () => void;
}) {
  const data = useAsync(() => getMyAvailability(workspaceId, meetingId), [workspaceId, meetingId]);
  const [prefs, setPrefs] = useState<Map<string, SlotPreference>>(new Map());
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<Error>();
  const brush = useRef<SlotPreference | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!data.data) return;
    setPrefs(
      new Map(
        data.data.mySlots.filter((s) => s.source === 'MANUAL').map((s) => [slotKey(s.startAt), s.preference] as const),
      ),
    );
    setDirty(false);
  }, [data.data]);

  const paint = useCallback((cell: string, pref: SlotPreference) => {
    setPrefs((prev) => {
      if ((prev.get(cell) ?? 'UNAVAILABLE') === pref) return prev;
      const next = new Map(prev);
      if (pref === 'UNAVAILABLE') next.delete(cell);
      else next.set(cell, pref);
      return next;
    });
    setDirty(true);
  }, []);

  useEffect(() => {
    let frame = 0;
    let previousTime = 0;
    let lastCell: string | undefined;
    let pointer: { x: number; y: number } | null = null;

    const stop = () => {
      brush.current = null;
      pointer = null;
      lastCell = undefined;
      previousTime = 0;
      cancelAnimationFrame(frame);
      frame = 0;
    };
    const paintAt = (x: number, y: number) => {
      const button = document.elementFromPoint(x, y)?.closest<HTMLButtonElement>('[data-slot-key]');
      const cell = button?.dataset.slotKey;
      if (!cell || !brush.current || cell === lastCell || !scrollRef.current?.contains(button!)) return;
      lastCell = cell;
      paint(cell, brush.current);
    };
    const edgeSpeed = (position: number, start: number, end: number) => {
      const edge = Math.min(40, (end - start) / 3);
      if (position < start + edge) return -Math.min(1, (start + edge - position) / edge);
      if (position > end - edge) return Math.min(1, (position - end + edge) / edge);
      return 0;
    };
    const tick = (time: number) => {
      const scroll = scrollRef.current;
      if (!scroll || !pointer || !brush.current) return stop();
      const bounds = scroll.getBoundingClientRect();
      const page = scroll.closest('main');
      const pageBounds = page?.getBoundingClientRect();
      const top = Math.max(bounds.top, pageBounds?.top ?? 0, 0);
      const bottom = Math.min(bounds.bottom, pageBounds?.bottom ?? innerHeight, innerHeight);
      const left = Math.max(bounds.left, pageBounds?.left ?? 0, 0);
      const right = Math.min(bounds.right, pageBounds?.right ?? innerWidth, innerWidth);
      const dt = previousTime ? Math.min(time - previousTime, 32) : 16;
      previousTime = time;

      if (bottom > top && right > left && pointer.x >= left - 40 && pointer.x <= right + 40 && pointer.y >= top - 40 && pointer.y <= bottom + 40) {
        const dy = edgeSpeed(pointer.y, top, bottom) * 660 * dt / 1000;
        const dx = edgeSpeed(pointer.x, left, right) * 500 * dt / 1000;
        const previousTop = scroll.scrollTop;
        scroll.scrollBy({ top: dy, left: dx, behavior: 'instant' });
        // Reveal the remainder of a grid clipped by the page after its own scroll reaches an end.
        if (page && Math.abs(scroll.scrollTop - previousTop) < 0.5 &&
          ((dy > 0 && bounds.bottom > bottom + 1) || (dy < 0 && bounds.top < top - 1))) {
          page.scrollBy({ top: dy, behavior: 'instant' });
        }
        const headingBottom = scroll.querySelector('thead th')?.getBoundingClientRect().bottom ?? top;
        if (bottom > Math.max(top, headingBottom) + 4) {
          paintAt(Math.min(right - 3, Math.max(left + 3, pointer.x)),
            Math.min(bottom - 3, Math.max(top + 3, headingBottom + 3, pointer.y)));
        }
      }
      frame = requestAnimationFrame(tick);
    };
    const start = (event: MouseEvent) => {
      if (event.button !== 0 || !brush.current || !(event.target instanceof Element) ||
        !scrollRef.current?.contains(event.target.closest('[data-slot-key]'))) return;
      pointer = { x: event.clientX, y: event.clientY };
      lastCell = event.target.closest<HTMLButtonElement>('[data-slot-key]')?.dataset.slotKey;
      cancelAnimationFrame(frame);
      previousTime = 0;
      frame = requestAnimationFrame(tick);
    };
    const move = (event: MouseEvent) => {
      if (!brush.current) return;
      if (!(event.buttons & 1)) return stop();
      const next = { x: event.clientX, y: event.clientY };
      if (pointer) {
        const steps = Math.ceil(Math.hypot(next.x - pointer.x, next.y - pointer.y) / 10);
        for (let i = 1; i <= steps; i++) {
          paintAt(pointer.x + (next.x - pointer.x) * i / steps, pointer.y + (next.y - pointer.y) * i / steps);
        }
      }
      pointer = next;
      paintAt(next.x, next.y);
    };
    window.addEventListener('mousedown', start);
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', stop);
    window.addEventListener('blur', stop);
    window.addEventListener('pointercancel', stop);
    document.addEventListener('visibilitychange', stop);
    return () => {
      stop();
      window.removeEventListener('mousedown', start);
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', stop);
      window.removeEventListener('blur', stop);
      window.removeEventListener('pointercancel', stop);
      document.removeEventListener('visibilitychange', stop);
    };
  }, [paint]);

  const grid = useMemo(() => (data.data ? buildGrid(data.data) : null), [data.data]);
  const busy = useMemo(() => new Set((data.data?.busySlots ?? []).map(slotKey)), [data.data]);

  if (data.loading) return <Loading />;
  if (data.error) return <ErrorBox error={data.error} onRetry={data.reload} />;
  if (!data.data || !grid) return null;

  const save = async () => {
    setSaving(true);
    setError(undefined);
    try {
      const slots = [...prefs.entries()].map(([startAt, preference]) => ({ startAt, preference }));
      data.setData(await saveAvailability(workspaceId, meetingId, slots));
      onSaved();
    } catch (err) {
      setError(err as Error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="availability-panel" aria-label="내 참여 가능 시간">
      <div className="availability-panel__head">
        <div className="flex min-w-0 items-center gap-2.5">
          <span className="availability-panel__icon"><MeetingFormIcon name="calendar" className="h-[18px] w-[18px]" /></span>
          <div className="min-w-0">
            <strong className="block text-sm">내 참여 가능 시간</strong>
            <span className="mt-0.5 block text-xs text-[var(--muted)]">함께할 수 있는 시간을 알려주세요</span>
          </div>
        </div>
        <button type="button" className="btn btn-primary btn-small availability-save" onClick={save} disabled={saving}>
          <MeetingFormIcon name="check" />{saving ? '저장 중...' : '응답 저장'}
        </button>
      </div>

      <div className="availability-panel__guide">
        <span>드래그로 선택 · 가장자리에서 자동 스크롤</span>
        <span className="availability-unit"><MeetingFormIcon name="clock" className="h-3.5 w-3.5" />{data.data.slotMinutes}분 단위 · 한국 시간</span>
      </div>
      <div className="availability-legend" aria-label="시간 상태 안내">
        <span><i className="availability-swatch is-available" />가능</span>
        <span><i className="availability-swatch is-not-preferred" />비선호</span>
        <span><i className="availability-swatch is-unavailable" />불가</span>
        <span><i className="availability-swatch is-busy" />캘린더 일정</span>
      </div>
      {error && <div className="px-4"><ErrorBox error={error} /></div>}
      <div className="availability-calendar">
        <div ref={scrollRef} className="avail-scroll availability-calendar__scroll" role="region" aria-label="시간표, 좌우와 위아래로 스크롤할 수 있습니다" tabIndex={0}>
          <table className="avail-grid avail-grid--boundaries availability-calendar__grid"
            style={{ minWidth: 56 + grid.days.length * 72 }}
            aria-label="참여 가능 시간 선택">
            <colgroup><col style={{ width: 56 }} />{grid.days.map((d) => <col key={d.toDateString()} />)}</colgroup>
            <thead>
              <tr>
                <th className="availability-axis-title" scope="col"><MeetingFormIcon name="clock" className="h-3.5 w-3.5" /><span className="sr-only">시간</span></th>
                {grid.days.map((d) => (
                  <th key={d.toDateString()} scope="col" aria-label={formatDate(d)}>
                    <div className={`availability-day${d.getDay() === 0 || d.getDay() === 6 ? ' is-weekend' : ''}`}>
                      <span>{['일요일', '월요일', '화요일', '수요일', '목요일', '금요일', '토요일'][d.getDay()]}</span>
                      <strong>{d.getMonth() + 1}.{String(d.getDate()).padStart(2, '0')}</strong>
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {grid.times.map((t) => (
                <tr key={t} className={t.endsWith(':00') ? 'availability-hour-start' : undefined}>
                  <th scope="row" aria-label={t}>
                    {t.endsWith(':00') && <span className="availability-time" aria-hidden="true">{t}</span>}
                  </th>
                  {grid.days.map((d) => {
                    const cell = grid.cell(d, t);
                    if (!cell) return <td key={d.toDateString()} className="cell-disabled" aria-label={`${formatDate(d)} ${t}, 후보 기간 밖`} />;
                    const endTime = formatTime(new Date(fromLocalIso(cell).getTime() + data.data!.slotMinutes * 60_000));
                    if (busy.has(cell)) return <td key={cell} className="cell-busy" title={`${formatDate(d)} ${t}–${endTime} · 캘린더 일정`} aria-label={`${formatDate(d)} ${t}–${endTime}, 캘린더 일정`} />;
                    const pref = prefs.get(cell) ?? 'UNAVAILABLE';
                    return (
                      <td key={cell} className={`cell-${pref.toLowerCase()}`}>
                        <button type="button" className="availability-cell" data-slot-key={cell}
                          aria-label={`${formatDate(d)} ${t}–${endTime}, ${PREF_TEXT[pref]}`}
                          aria-pressed={pref !== 'UNAVAILABLE'}
                          title={`${formatDate(d)} ${t}–${endTime} · ${PREF_TEXT[pref]}`}
                          onMouseDown={(e) => {
                            if (e.button !== 0) return;
                            e.preventDefault();
                            brush.current = NEXT[pref];
                            paint(cell, NEXT[pref]);
                          }}
                          onClick={(e) => { if (e.detail === 0) paint(cell, NEXT[pref]); }}>
                          {pref === 'AVAILABLE' && <MeetingFormIcon name="check" className="h-3 w-3" />}
                          {pref === 'NOT_PREFERRED' && <span className="availability-preference-dot" aria-hidden="true" />}
                        </button>
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row"><span className="availability-time">{String(data.data.dayEndHour).padStart(2, '0')}:00</span></th>
                <td colSpan={grid.days.length} aria-hidden="true" />
              </tr>
            </tfoot>
          </table>
        </div>
      </div>
      <div className="availability-panel__foot">
        <span className={`availability-response${dirty ? ' is-dirty' : ''}`}>
          {dirty ? <><i />저장하지 않은 변경사항</> : data.data.hasResponded ? <><MeetingFormIcon name="check" className="h-3.5 w-3.5" />응답 완료</> : '선택한 시간은 저장 후 팀에 반영돼요'}
        </span>
        <span>가능 → 비선호 → 불가</span>
      </div>
      <p className="availability-calendar-note">
        <MeetingFormIcon name="calendar" className="h-3.5 w-3.5" />
        {data.data.calendarConnected ? 'Google Calendar의 일정은 회색으로 표시돼요.' : 'Google Calendar를 연결하면 일정이 있는 시간을 자동으로 표시해요.'}
      </p>
    </section>
  );
}

function buildGrid(a: AvailabilityResponse) {
  const start = fromLocalIso(a.candidateStartAt);
  const end = fromLocalIso(a.candidateEndAt);
  const days: Date[] = [];
  for (let d = startOfDay(start); d < end; d = addDays(d, 1)) days.push(d);
  const times: string[] = [];
  for (let minutes = a.dayStartHour * 60; minutes < a.dayEndHour * 60; minutes += a.slotMinutes) {
    times.push(`${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`);
  }
  /** 후보 기간 안의 칸이면 키, 아니면 null */
  const cell = (day: Date, time: string) => {
    const [h, m] = time.split(':').map(Number);
    const s = new Date(day);
    s.setHours(h, m, 0, 0);
    const e = new Date(s.getTime() + a.slotMinutes * 60_000);
    return s >= start && e <= end ? toLocalIso(s) : null;
  };
  return { days, times, cell };
}

// ===================== 추천 · 확정 =====================

/** 추천 후보를 한 번에 보여 주는 개수 */
const PAGE_SIZE = 5;

/** 시안 오른쪽 카드: 추천 시간 후보를 고르고 "이 시간으로 확정" */
function Recommendations({
  workspaceId,
  meeting,
  onConfirm,
}: {
  workspaceId: string;
  meeting: MeetingDetailResponse;
  onConfirm: (startAt: string, force: boolean) => void;
}) {
  const [candidates, setCandidates] = useState<TimeCandidate[] | null>(null);
  const [visible, setVisible] = useState(PAGE_SIZE);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error>();
  const [pickedId, setPickedId] = useState<string | number | null>(null);
  const responded = meeting.participants.filter((p) => p.hasResponded).length;

  const recommend = async () => {
    setLoading(true);
    setError(undefined);
    try {
      setCandidates(await recommendTimes(workspaceId, meeting.meetingId));
      setVisible(PAGE_SIZE);
      setPickedId(null);
    } catch (err) {
      setError(err as Error);
    } finally {
      setLoading(false);
    }
  };

  // 응답이 있으면 들어오자마자 추천을 보여준다.
  useEffect(() => {
    if (responded > 0) void recommend();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const picked = candidates?.find((c) => c.candidateId === pickedId) ?? candidates?.[0] ?? null;

  // 확정 직전에 참여자들의 지금 일정을 다시 확인한다. 응답한 뒤에 다른 회의가 잡혔을 수 있다.
  const confirm = async () => {
    if (!picked) return;
    let conflicts;
    try {
      conflicts = await getConflicts(workspaceId, meeting.meetingId, picked.startAt);
    } catch (err) {
      setError(err as Error);
      return;
    }
    if (conflicts.length === 0) {
      if (window.confirm(`${formatDateTime(picked.startAt)}로 회의를 확정할까요? 팀 캘린더와 참여자 캘린더에 일정과 화상 회의 링크가 등록됩니다.`)) {
        onConfirm(picked.startAt, false);
      }
      return;
    }
    const lines = conflicts
      .map((c) => `· ${c.name ?? '참여자'}님: ${c.reasons.map((r) => `'${r}'`).join(', ')}`)
      .join('\n');
    const proceed = window.confirm(
      `${formatDateTime(picked.startAt)}에 이미 다른 일정이 있는 참여자가 있어요.\n${lines}\n\n` +
        '그래도 확정할까요?\n(취소를 누르면 지금 일정을 기준으로 다시 추천합니다.)',
    );
    if (proceed) onConfirm(picked.startAt, true);
    else void recommend();
  };

  return (
    <div className="card meeting-side">
      <div>
        <div className="flex items-center gap-2">
          <span className="text-[var(--primary)]">
            <NavIcon name="analyses" />
          </span>
          <strong className="text-base">추천 시간</strong>
          {candidates && <span className="badge badge-primary">후보 {candidates.length}개</span>}
          <span className="flex-1" />
          <button type="button" className="analysis-ghost" onClick={recommend} disabled={loading || responded === 0}>
            {loading ? '계산 중...' : '↻ 다시 추천'}
          </button>
        </div>
        <div className="mt-1 text-xs text-[var(--muted)]">팀원이 가장 많이 참여할 수 있는 시간이에요.</div>
      </div>

      {responded === 0 && (
        <span className="text-[13px] text-[var(--muted)]">가용 시간을 입력한 참여자가 있어야 추천할 수 있어요.</span>
      )}
      {error && <ErrorBox error={error} />}
      {candidates && candidates.length === 0 && (
        <span className="text-[13px] text-[var(--muted)]">
          모두가 가능한 시간이 없습니다. 후보 기간을 늘리거나 응답을 더 받아 보세요.
        </span>
      )}

      {candidates && candidates.length > 0 && (
        <>
          <div className="flex flex-col gap-2.5">
            {candidates.slice(0, visible).map((c) => {
              const active = picked?.candidateId === c.candidateId;
              const all = c.availableCount === c.participantCount;
              return (
                <button
                  key={c.candidateId}
                  type="button"
                  className={`meeting-cand${active ? ' active' : ''}`}
                  onClick={() => setPickedId(c.candidateId)}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="flex items-center gap-2">
                      <span className={`meeting-radio${active ? ' on' : ''}`} />
                      <strong className="text-sm">
                        {formatDateTime(c.startAt)} - {formatTime(c.endAt)}
                      </strong>
                    </span>
                    {c.rank === 1 && <span className="text-xs font-bold text-[var(--primary)]">추천</span>}
                  </div>
                  <div className="pl-6 text-xs font-semibold">
                    <span className={all ? 'text-[var(--success)]' : 'text-[var(--muted)]'}>
                      {all ? `✓ ${c.participantCount}명 모두 참석 가능` : `${c.availableCount}명 가능`}
                      {c.nonPreferredCount > 0 && ` · ${c.nonPreferredCount}명 비선호`}
                      {c.respondedCount < c.participantCount && ` · 미응답 ${c.participantCount - c.respondedCount}명`}
                    </span>
                    {c.unavailableNames.length > 0 && (
                      <span className="block text-[var(--muted)]">불가: {c.unavailableNames.join(', ')}</span>
                    )}
                  </div>
                </button>
              );
            })}
            {candidates.length > visible && (
              <button type="button" className="btn btn-small" onClick={() => setVisible(visible + PAGE_SIZE)}>
                더 보기 ({candidates.length - visible}개 남음)
              </button>
            )}
          </div>

          <div className="flex flex-col gap-2.5 border-t border-[var(--border-soft)] pt-3">
            <div className="text-xs font-bold text-[var(--muted)]">선택한 시간</div>
            <div className="rounded-[10px] bg-[var(--surface-2)] px-3 py-2.5 text-sm font-bold">
              {picked ? `${formatDateTime(picked.startAt)} - ${formatTime(picked.endAt)}` : '-'}
            </div>
            {meeting.canManage ? (
              <button type="button" className="btn btn-primary !h-11 w-full" onClick={confirm} disabled={!picked}>
                이 시간으로 확정
              </button>
            ) : (
              <div className="text-center text-xs text-[var(--muted)]">회의를 만든 사람이나 팀장·관리자가 확정할 수 있어요.</div>
            )}
            <div className="text-center text-xs text-[var(--muted)]">📅 확정하면 팀 캘린더에 등록돼요.</div>
          </div>
        </>
      )}
    </div>
  );
}
