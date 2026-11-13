// UI-10 회의 상세 · 안건 · 가용 시간 · 추천 · 확정 (담당: A)
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import {
  addAgenda,
  cancelMeeting,
  confirmMeeting,
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
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';
import { MUTED } from '../../components/ui';
import PageHeader from '../../components/PageHeader';
import NavIcon from '../../layouts/NavIcon';
import { addDays, formatDate, formatDateTime, formatTime, fromLocalIso, startOfDay, toLocalIso } from '../../utils/date';

const RISK_TEXT = { NORMAL: '정상', WARNING: '주의', DANGER: '위험' } as const;

export default function MeetingDetailPage() {
  const { workspaceId = '', meetingId = '' } = useParams();
  const meeting = useAsync(() => getMeeting(workspaceId, meetingId), [workspaceId, meetingId]);
  const [actionError, setActionError] = useState<Error>();
  const [editing, setEditing] = useState(false);
  const m = meeting.data;

  const run = async (fn: () => Promise<MeetingDetailResponse | void>) => {
    setActionError(undefined);
    try {
      const result = await fn();
      if (result) meeting.setData(result);
      else meeting.reload();
    } catch (err) {
      setActionError(err as Error);
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
        <div className="card meeting-main">
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
              {m.canManage && scheduling && !editing && (
                <button type="button" className="btn btn-small" onClick={() => setEditing(true)}>
                  수정
                </button>
              )}
              {m.canManage && m.status !== 'CANCELED' && (
                <button
                  type="button"
                  className="btn btn-small btn-danger"
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
                  Google Calendar 등록에 실패했습니다. 연동 상태를 확인한 뒤 다시 시도하세요.
                  {m.canManage && (
                    <button className="btn btn-small" onClick={() => run(() => retryCalendar(workspaceId, m.meetingId))}>
                      다시 등록
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          <Agendas workspaceId={workspaceId} meeting={m} onChange={run} />

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
            onConfirm={(startAt) => run(() => confirmMeeting(workspaceId, m.meetingId, startAt))}
          />
        )}
      </div>
    </section>
  );
}

function Agendas({
  workspaceId,
  meeting,
  onChange,
}: {
  workspaceId: string;
  meeting: MeetingDetailResponse;
  onChange: (fn: () => Promise<MeetingDetailResponse>) => void;
}) {
  const editable = meeting.canManage && meeting.status !== 'CANCELED';
  const [adding, setAdding] = useState(false);
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
          <button type="button" className="analysis-ghost" onClick={() => setAdding(!adding)}>
            {adding ? '닫기' : '+ 안건 추가'}
          </button>
        )}
      </div>
      {meeting.agendas.length === 0 && <span className="text-[13px] text-[var(--muted)]">안건이 없습니다.</span>}
      {meeting.agendas.map((a) => {
        const done = a.task?.status === 'DONE' || a.task?.progress === 100;
        return (
          <div key={a.agendaId} className="meeting-agenda">
            <div className="flex min-w-0 items-center gap-2">
              <span className="text-[var(--muted)]">
                <NavIcon name="tasks" />
              </span>
              {a.task ? (
                <Link to={`/workspaces/${workspaceId}/tasks/${a.taskId}`} className="truncate text-sm font-semibold text-[var(--text)] no-underline">
                  {a.task.title}
                </Link>
              ) : (
                <span className="text-sm text-[var(--muted)]">(삭제된 팀 작업)</span>
              )}
              {a.task && a.task.riskLevel !== 'NORMAL' && !done && (
                <span className={`badge ${a.task.riskLevel === 'DANGER' ? 'badge-danger' : 'badge-warning'} !text-[10px]`}>
                  {RISK_TEXT[a.task.riskLevel]}
                </span>
              )}
            </div>
            <div className="flex flex-shrink-0 items-center gap-2.5">
              {a.task && (
                <>
                  <span className={`text-xs font-bold ${done ? 'text-[var(--success)]' : 'text-[var(--muted)]'}`}>
                    {done ? '완료 · 100%' : `진행 중 · ${a.task.progress}%`}
                  </span>
                  <div className="h-2 w-[88px] overflow-hidden rounded-full bg-[var(--surface-2)]">
                    <div
                      className="h-full rounded-full"
                      style={{
                        width: `${done ? 100 : a.task.progress}%`,
                        background: done ? 'var(--success)' : 'var(--brand-grad)',
                      }}
                    />
                  </div>
                </>
              )}
              {editable && (
                <button
                  type="button"
                  className="analysis-ghost"
                  onClick={() => onChange(() => deleteAgenda(workspaceId, meeting.meetingId, a.agendaId))}
                >
                  빼기
                </button>
              )}
            </div>
          </div>
        );
      })}
      {adding && (
        <div className="tags">
          {candidates.loading && <Loading />}
          {candidates.data
            ?.filter((t) => !used.has(t.taskId))
            .map((t) => (
              <button
                key={t.taskId}
                className="tag selectable"
                onClick={() => onChange(() => addAgenda(workspaceId, meeting.meetingId, t.taskId))}
              >
                + {t.title}
              </button>
            ))}
          {candidates.data && candidates.data.filter((t) => !used.has(t.taskId)).length === 0 && (
            <span className="muted small">추가할 수 있는 진행 중 팀 작업이 없습니다.</span>
          )}
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

  useEffect(() => {
    if (!data.data) return;
    setPrefs(
      new Map(
        data.data.mySlots.filter((s) => s.source === 'MANUAL').map((s) => [slotKey(s.startAt), s.preference] as const),
      ),
    );
    setDirty(false);
  }, [data.data]);

  useEffect(() => {
    const stop = () => (brush.current = null);
    window.addEventListener('mouseup', stop);
    return () => window.removeEventListener('mouseup', stop);
  }, []);

  const grid = useMemo(() => (data.data ? buildGrid(data.data) : null), [data.data]);
  const busy = useMemo(() => new Set((data.data?.busySlots ?? []).map(slotKey)), [data.data]);

  if (data.loading) return <Loading />;
  if (data.error) return <ErrorBox error={data.error} onRetry={data.reload} />;
  if (!data.data || !grid) return null;

  const paint = (cell: string, pref: SlotPreference) => {
    setPrefs((prev) => {
      const next = new Map(prev);
      if (pref === 'UNAVAILABLE') next.delete(cell);
      else next.set(cell, pref);
      return next;
    });
    setDirty(true);
  };

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
    <div className="space-y-3 rounded-xl border border-[var(--border)] p-4">
      <div className="row">
        <strong className="text-sm">내 가용 시간</strong>
        {data.data.hasResponded && !dirty && <span className="badge badge-success">응답 완료</span>}
        <span className="spacer" />
        <button className="btn btn-primary btn-small" onClick={save} disabled={saving}>
          {saving ? '저장 중...' : '저장'}
        </button>
      </div>
      <p className="muted small">
        칸을 누르거나 끌어서 칠하세요. 누를 때마다 가능 → 비선호 → 불가능으로 바뀝니다. 칠하지 않은 칸은 불가능입니다.
        {data.data.calendarConnected
          ? ' 회색 칸은 내 Google Calendar에 일정이 있어 자동으로 불가능 처리됩니다.'
          : ' Google Calendar를 연동하면 일정이 있는 시간이 자동으로 불가능 처리됩니다.'}
      </p>
      <div className="legend-row small">
        <span className="legend available" /> 가능 <span className="legend not_preferred" /> 비선호{' '}
        <span className="legend busy" /> 캘린더 일정
      </div>
      {error && <ErrorBox error={error} />}
      <div className="avail-scroll">
        <table className="avail-grid" onMouseLeave={() => (brush.current = null)}>
          <thead>
            <tr>
              <th />
              {grid.days.map((d) => (
                <th key={d.toDateString()}>{formatDate(d)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {grid.times.map((t) => (
              <tr key={t}>
                <th className="small">{t.endsWith(':00') ? t : ''}</th>
                {grid.days.map((d) => {
                  const cell = grid.cell(d, t);
                  if (!cell) return <td key={d.toDateString()} className="cell-disabled" />;
                  if (busy.has(cell)) return <td key={cell} className="cell-busy" title="캘린더 일정" />;
                  const pref = prefs.get(cell) ?? 'UNAVAILABLE';
                  return (
                    <td
                      key={cell}
                      className={`cell-${pref.toLowerCase()}`}
                      title={`${formatDate(d)} ${t} ${PREF_TEXT[pref]}`}
                      onMouseDown={(e) => {
                        e.preventDefault();
                        brush.current = NEXT[pref];
                        paint(cell, NEXT[pref]);
                      }}
                      onMouseEnter={() => brush.current && paint(cell, brush.current)}
                    />
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
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
  onConfirm: (startAt: string) => void;
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

  const confirm = () => {
    if (!picked) return;
    if (window.confirm(`${formatDateTime(picked.startAt)}로 회의를 확정할까요? 팀 캘린더에 일정과 화상 회의 링크가 등록됩니다.`)) {
      onConfirm(picked.startAt);
    }
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
              <div className="text-center text-xs text-[var(--muted)]">회의를 만든 사람이 확정할 수 있어요.</div>
            )}
            <div className="text-center text-xs text-[var(--muted)]">📅 확정하면 팀 캘린더에 등록돼요.</div>
          </div>
        </>
      )}
    </div>
  );
}
