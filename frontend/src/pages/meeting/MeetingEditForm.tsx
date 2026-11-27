// UI-10 회의 정보 수정 (담당: A)
import { useState, type FormEvent } from 'react';
import { updateMeeting, type MeetingDetailResponse } from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { toInputValue } from '../../utils/date';
import MeetingDateTimePicker from './MeetingDateTimePicker';
import {
  MeetingFormShell, MeetingInformationFields, MeetingCandidatePeriodFields, MeetingParticipantsField,
  MeetingFormSummary, MeetingSectionHeading,
} from './MeetingFormFields';

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
  const invalidPeriod = !confirmed && form.candidateStartAt >= form.candidateEndAt;
  const missingTime = confirmed ? !form.startAt : !form.candidateStartAt || !form.candidateEndAt;
  const confirmedEnd = confirmed && form.startAt
    ? toInputValue(new Date(new Date(form.startAt).getTime() + form.durationMinutes * 60_000)) : '';
  const change = (patch: Partial<typeof form>) => setForm((current) => ({ ...current, ...patch }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!form.title.trim()) {
      setError(new Error('회의 제목을 입력해 주세요.'));
      return;
    }
    if (missingTime) {
      setError(new Error('회의 날짜와 시간을 선택해 주세요.'));
      return;
    }
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
    <MeetingFormShell title="회의 수정" description="회의 정보와 참여자를 확인하고, 변경할 내용을 정리해요."
      saving={saving} onSubmit={submit} onClose={onClose} error={error}
      submitDisabled={!form.title.trim() || form.participantIds.length === 0 || members.loading || Boolean(members.error) || invalidPeriod || missingTime}
      submitLabel="변경사항 저장" savingLabel="변경사항 저장 중…"
      footerHint={confirmed ? '확정 상태를 유지하며 회의 정보를 수정해요.' : '변경 내용을 확인한 뒤 저장해 주세요.'}
      sidebar={<>
        <MeetingParticipantsField members={members} value={form.participantIds} lockedUserId={meeting.createdBy}
          onChange={(participantIds) => change({ participantIds })} />
        <MeetingFormSummary value={form} agendaCount={meeting.agendas.length} confirmed={confirmed}
          startAt={confirmed ? form.startAt : form.candidateStartAt} endAt={confirmed ? confirmedEnd : form.candidateEndAt} />
        <p className="m-0 mt-3 px-1 text-[11px] leading-5 text-[var(--muted)]">
          {confirmed ? '변경한 내용은 연결된 캘린더에도 반영돼요. 반영에 실패하면 다시 시도할 수 있어요.' : '만든 사람은 참여자로 유지돼요. 참여자를 바꾸면 다음 시간 추천에 반영돼요.'}
        </p>
      </>}>
      <MeetingInformationFields value={form} onChange={change} allowUnspecified />
      {confirmed ? (
        <section aria-label="확정 회의 일정" className="border-t border-[var(--border-soft)] pt-5">
          <MeetingSectionHeading number="02" title="확정 일정" description="회의가 시작할 시간을 선택하세요. 종료 시각은 소요 시간에 맞춰 계산돼요." />
          <div className="min-w-0 rounded-xl bg-[var(--surface-2)] p-3 text-xs font-semibold text-[var(--muted)]">
            시작 <span className="text-[var(--primary)]">*</span>
            <MeetingDateTimePicker label="확정 시작" disabled={saving} value={form.startAt || form.candidateStartAt}
              onChange={(startAt) => change({ startAt })} />
          </div>
          <p className="m-0 mt-2 text-[11px] leading-5 text-[var(--muted)]">모든 날짜와 시간은 한국 시간 기준이에요.</p>
        </section>
      ) : <MeetingCandidatePeriodFields value={form} saving={saving} invalid={invalidPeriod} onChange={change} />}
      {!confirmed && periodChanged && (
        <p role="status" className="m-0 rounded-xl border border-[var(--border)] bg-[var(--surface-2)] p-3 text-xs leading-5 text-[var(--muted)]">
          후보 기간을 바꾸면 모든 참여자가 가용 시간을 다시 입력해야 해요.
        </p>
      )}
    </MeetingFormShell>
  );
}
