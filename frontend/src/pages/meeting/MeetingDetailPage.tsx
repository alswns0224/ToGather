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
import { STATUS_TEXT } from './MeetingListPage';
import MeetingEditForm from './MeetingEditForm';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';
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

  if (meeting.loading && !m) return <Loading />;
  if (meeting.error) return <ErrorBox error={meeting.error} onRetry={meeting.reload} />;
  if (!m) return null;

  const scheduling = m.status === 'SCHEDULING';

  return (
    <section className="stack">
      <Link to={`/workspaces/${workspaceId}/meetings`} className="small">
        ← 회의 목록
      </Link>
      <div className="row">
        <h1>{m.title}</h1>
        <span className={`badge status-${m.status.toLowerCase()}`}>{STATUS_TEXT[m.status]}</span>
        <span className="spacer" />
        {m.canManage && scheduling && !editing && (
          <button className="btn" onClick={() => setEditing(true)}>
            수정
          </button>
        )}
        {m.canManage && m.status !== 'CANCELED' && (
          <button
            className="btn btn-danger"
            onClick={() =>
              window.confirm('회의를 취소할까요? 확정된 회의면 캘린더 일정도 삭제됩니다.') &&
              run(() => cancelMeeting(workspaceId, m.meetingId))
            }
          >
            회의 취소
          </button>
        )}
      </div>
      <p className="muted">
        {m.durationMinutes}분 · {m.meetingMode ?? '진행 방식 미정'} · 후보 기간 {formatDateTime(m.candidateStartAt)} ~{' '}
        {formatDateTime(m.candidateEndAt)}
      </p>
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

      {m.status === 'CONFIRMED' && m.startAt && (
        <div className="card stack confirmed">
          <strong>
            확정: {formatDateTime(m.startAt)} ~ {m.endAt && formatTime(m.endAt)}
          </strong>
          <div className="row">
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
        <>
          <Responses meeting={m} />
          {m.participating ? (
            <AvailabilityGrid
              key={`grid-${m.candidateStartAt}-${m.candidateEndAt}-${m.durationMinutes}`}
              workspaceId={workspaceId}
              meetingId={m.meetingId}
              onSaved={meeting.reload}
            />
          ) : (
            <p className="muted">회의 참여자만 가용 시간을 입력할 수 있습니다.</p>
          )}
          <Recommendations
            key={`rec-${m.candidateStartAt}-${m.candidateEndAt}-${m.durationMinutes}`}
            workspaceId={workspaceId}
            meeting={m}
            onConfirm={(startAt) => run(() => confirmMeeting(workspaceId, m.meetingId, startAt))}
          />
        </>
      )}
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
    <div className="card stack">
      <div className="row">
        <strong>안건</strong>
        <span className="spacer" />
        {editable && (
          <button className="btn btn-small" onClick={() => setAdding(!adding)}>
            {adding ? '닫기' : '+ 안건 추가'}
          </button>
        )}
      </div>
      {meeting.agendas.length === 0 && <span className="muted small">안건이 없습니다.</span>}
      <ol className="list">
        {meeting.agendas.map((a) => (
          <li key={a.agendaId} className="row">
            <span>{a.orderNo}.</span>
            {a.task ? (
              <>
                <span>{a.task.title}</span>
                <span className="small muted">
                  {a.task.status === 'DONE' ? '완료' : '진행 중'} · {a.task.progress}%
                  {a.task.dueDate && ` · 마감 ${formatDate(a.task.dueDate)}`}
                </span>
                <span className={`badge risk-${a.task.riskLevel.toLowerCase()}`}>{RISK_TEXT[a.task.riskLevel]}</span>
                <Link className="small" to={`/workspaces/${workspaceId}/tasks/${a.taskId}`}>
                  관련 자료
                </Link>
              </>
            ) : (
              <span className="muted">(삭제된 Task)</span>
            )}
            <span className="spacer" />
            {editable && (
              <button
                className="btn btn-small"
                onClick={() => onChange(() => deleteAgenda(workspaceId, meeting.meetingId, a.agendaId))}
              >
                빼기
              </button>
            )}
          </li>
        ))}
      </ol>
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
            <span className="muted small">추가할 수 있는 진행 중 Task가 없습니다.</span>
          )}
        </div>
      )}
    </div>
  );
}

function Responses({ meeting }: { meeting: MeetingDetailResponse }) {
  const responded = meeting.participants.filter((p) => p.hasResponded).length;
  return (
    <div className="card stack">
      <strong>
        가용 시간 응답 ({responded}/{meeting.participants.length})
      </strong>
      <div className="tags">
        {meeting.participants.map((p) => (
          <span key={p.userId} className={`tag ${p.hasResponded ? 'tag-done' : ''}`}>
            {p.hasResponded ? '✓' : '…'} {p.name}
          </span>
        ))}
      </div>
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
    <div className="card stack">
      <div className="row">
        <strong>내 가용 시간</strong>
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
  const responded = meeting.participants.filter((p) => p.hasResponded).length;

  const recommend = async () => {
    setLoading(true);
    setError(undefined);
    try {
      setCandidates(await recommendTimes(workspaceId, meeting.meetingId));
      setVisible(PAGE_SIZE);
    } catch (err) {
      setError(err as Error);
    } finally {
      setLoading(false);
    }
  };

  const confirm = (c: TimeCandidate) => {
    if (window.confirm(`${formatDateTime(c.startAt)}로 회의를 확정할까요? 팀 캘린더에 일정과 화상 회의 링크가 등록됩니다.`)) {
      onConfirm(c.startAt);
    }
  };

  return (
    <div className="card stack">
      <div className="row">
        <strong>회의 시간 추천</strong>
        <span className="spacer" />
        <button className="btn btn-small" onClick={recommend} disabled={loading || responded === 0}>
          {loading ? '계산 중...' : candidates ? '다시 추천' : '추천 받기'}
        </button>
      </div>
      {responded === 0 && <span className="muted small">가용 시간을 입력한 참여자가 있어야 추천할 수 있습니다.</span>}
      {error && <ErrorBox error={error} />}
      {candidates && candidates.length === 0 && (
        <span className="muted small">모두가 가능한 시간이 없습니다. 후보 기간을 늘리거나 응답을 더 받아 보세요.</span>
      )}
      {candidates && candidates.length > 0 && (
        <ul className="list">
          {candidates.slice(0, visible).map((c) => (
            <li key={c.candidateId} className="row">
              <span className={`badge ${c.rank === 1 ? 'badge-primary' : ''}`}>
                {c.tied ? '공동 ' : ''}
                {c.rank}위
              </span>
              <strong>
                {formatDateTime(c.startAt)} ~ {formatTime(c.endAt)}
              </strong>
              <span className="small">
                가능 {c.availableCount}/{c.respondedCount}
                {c.nonPreferredCount > 0 && ` · 비선호 ${c.nonPreferredCount}`}
                {c.respondedCount < c.participantCount && ` · 미응답 ${c.participantCount - c.respondedCount}`}
              </span>
              {c.unavailableNames.length > 0 && (
                <span className="small muted">불가: {c.unavailableNames.join(', ')}</span>
              )}
              <span className="spacer" />
              {meeting.canManage && (
                <button className="btn btn-primary btn-small" onClick={() => confirm(c)}>
                  확정
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {candidates && candidates.length > visible && (
        <button className="btn btn-small" onClick={() => setVisible(visible + PAGE_SIZE)}>
          더 보기 ({candidates.length - visible}개 남음)
        </button>
      )}
    </div>
  );
}
