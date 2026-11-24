// UI-10 회의 정보 수정 (담당: A)
import { useState, type FormEvent } from 'react';
import { updateMeeting, type MeetingDetailResponse } from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';

const DURATIONS = [30, 60, 90, 120, 180];

/** 조율 중에는 후보 기간, 확정 후에는 확정 시각을 수정한다. */
export default function MeetingEditForm({
  workspaceId,
  meeting,
  onSaved,
  onClose,
}: {
  workspaceId: string;
  meeting: MeetingDetailResponse;
  onSaved: (updated: MeetingDetailResponse) => void;
  onClose: () => void;
}) {
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const confirmed = meeting.status === 'CONFIRMED';
  const [form, setForm] = useState({
    title: meeting.title,
    durationMinutes: meeting.durationMinutes,
    candidateStartAt: meeting.candidateStartAt.slice(0, 16),
    candidateEndAt: meeting.candidateEndAt.slice(0, 16),
    startAt: meeting.startAt?.slice(0, 16) ?? '',
    participantIds: meeting.participants.map((p) => p.userId),
    meetingMode: meeting.meetingMode ?? '',
  });
  const [error, setError] = useState<Error>();
  const [saving, setSaving] = useState(false);

  const periodChanged =
    form.candidateStartAt !== meeting.candidateStartAt.slice(0, 16) ||
    form.candidateEndAt !== meeting.candidateEndAt.slice(0, 16);
  const removed = meeting.participants.filter((p) => !form.participantIds.includes(p.userId));
  const durations = DURATIONS.includes(meeting.durationMinutes) ? DURATIONS : [...DURATIONS, meeting.durationMinutes];

  const toggleMember = (id: number) =>
    setForm({
      ...form,
      participantIds: form.participantIds.includes(id)
        ? form.participantIds.filter((x) => x !== id)
        : [...form.participantIds, id],
    });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!confirmed && form.candidateStartAt >= form.candidateEndAt) {
      setError(new Error('후보 기간의 끝은 시작보다 뒤여야 합니다.'));
      return;
    }
    const warnings = [
      !confirmed && periodChanged && '후보 기간을 바꾸면 모든 참여자의 가용 시간 응답이 초기화됩니다.',
      removed.length > 0 && `${removed.map((p) => p.name).join(', ')}님이 참여자에서 빠지고 입력한 가용 시간도 지워집니다.`,
    ].filter(Boolean);
    if (warnings.length > 0 && !window.confirm(`${warnings.join('\n')}\n계속할까요?`)) return;

    setSaving(true);
    setError(undefined);
    try {
      const updated = await updateMeeting(workspaceId, meeting.meetingId, {
        title: form.title.trim(),
        durationMinutes: form.durationMinutes,
        ...(confirmed
          ? { startAt: meeting.startAt && form.startAt === meeting.startAt.slice(0, 16)
            ? meeting.startAt : `${form.startAt}:00` }
          : { candidateStartAt: `${form.candidateStartAt}:00`, candidateEndAt: `${form.candidateEndAt}:00` }),
        participantIds: form.participantIds,
        meetingMode: form.meetingMode,
      });
      onSaved(updated);
    } catch (err) {
      setError(err as Error);
      setSaving(false);
    }
  };

  return (
    <form className="card form" onSubmit={submit}>
      <h2>회의 수정</h2>
      {confirmed && (
        <p className="m-0 text-sm text-[var(--muted)]">
          확정 상태를 유지하면서 연결된 Calendar 일정도 수정합니다. 반영이 실패하면 회의 변경을 저장하고 다시 시도할 수 있어요.
        </p>
      )}
      <label>
        제목 *
        <input
          className="input"
          required
          disabled={saving}
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
            disabled={saving}
            value={form.durationMinutes}
            onChange={(e) => setForm({ ...form, durationMinutes: Number(e.target.value) })}
          >
            {durations.map((m) => (
              <option key={m} value={m}>
                {m >= 60 && m % 60 === 0 ? `${m / 60}시간` : `${m}분`}
              </option>
            ))}
          </select>
        </label>
        <label className="grow">
          진행 방식
          <select
            className="input"
            disabled={saving}
            value={form.meetingMode}
            onChange={(e) => setForm({ ...form, meetingMode: e.target.value })}
          >
            <option value="">미정</option>
            <option>온라인</option>
            <option>오프라인</option>
            <option>혼합</option>
          </select>
        </label>
      </div>
      {confirmed ? (
        <label>
          확정 시작 시각 *
          <input className="input" type="datetime-local" required disabled={saving} value={form.startAt}
            onChange={(e) => setForm({ ...form, startAt: e.target.value })} />
          <span className="muted small">종료 시각은 시작 시각과 소요 시간으로 계산합니다. 시간은 한국 시간 기준입니다.</span>
        </label>
      ) : <div className="row">
        <label className="grow">
          후보 기간 시작 *
          <input
            className="input"
            type="datetime-local"
            required
            disabled={saving}
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
            disabled={saving}
            value={form.candidateEndAt}
            onChange={(e) => setForm({ ...form, candidateEndAt: e.target.value })}
          />
        </label>
      </div>}
      {!confirmed && periodChanged && (
        <span className="muted small">후보 기간을 바꾸면 모든 참여자가 가용 시간을 다시 입력해야 합니다.</span>
      )}
      <fieldset>
        <legend>참여자</legend>
        {members.loading && <Loading />}
        {members.error && <ErrorBox error={members.error} onRetry={members.reload} />}
        <div className="tags">
          {members.data?.map((m) => (
            <label key={m.userId} className="tag selectable">
              <input
                type="checkbox"
                checked={form.participantIds.includes(m.userId)}
                disabled={saving || m.userId === meeting.createdBy}
                onChange={() => toggleMember(m.userId)}
              />
              {m.name}
              {m.userId === meeting.createdBy && <span className="muted small">(만든 사람)</span>}
            </label>
          ))}
        </div>
      </fieldset>
      {error && <ErrorBox error={error} />}
      <div className="row">
        <button className="btn btn-primary" disabled={saving || !form.title.trim() || form.participantIds.length === 0}>
          {saving ? '저장 중...' : '저장'}
        </button>
        <button type="button" className="btn" disabled={saving} onClick={onClose}>
          취소
        </button>
      </div>
    </form>
  );
}
