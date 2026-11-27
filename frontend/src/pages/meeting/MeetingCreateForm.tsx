// UI-10 회의 생성 — 수정 화면과 공통 입력 요소를 사용한다.
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { createMeeting, getAgendaCandidates } from './api';
import { getMembers } from '../workspace/api';
import { useAsync } from '../../hooks/useAsync';
import { useLoginUser } from '../../components/RequireAuth';
import { ErrorBox, Loading } from '../../components/Status';
import { addDays, formatDate, startOfDay, toInputValue } from '../../utils/date';
import NavIcon from '../../layouts/NavIcon';
import MeetingFormIcon from './MeetingFormIcon';
import {
  MeetingFormShell, MeetingInformationFields, MeetingCandidatePeriodFields, MeetingParticipantsField, MeetingFormSummary,
  MEETING_FORM_FIELD as FIELD, MEETING_SELECTED as SELECTED, MEETING_UNSELECTED as UNSELECTED,
} from './MeetingFormFields';

export default function MeetingCreateForm({ workspaceId, onClose }: { workspaceId: string; onClose: () => void }) {
  const user = useLoginUser();
  const navigate = useNavigate();
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const tasks = useAsync(() => getAgendaCandidates(workspaceId), [workspaceId]);
  const tomorrow = addDays(startOfDay(new Date()), 1);
  const [form, setForm] = useState({
    title: '',
    durationMinutes: 60,
    candidateStartAt: toInputValue(new Date(tomorrow.getTime() + 9 * 3600_000)),
    candidateEndAt: toInputValue(new Date(addDays(tomorrow, 7).getTime() + 22 * 3600_000)),
    participantIds: [user.userId],
    meetingMode: '온라인',
    agendaTaskIds: [] as number[],
    agendaTitles: [''],
  });
  const [error, setError] = useState<Error>();
  const [saving, setSaving] = useState(false);
  const agendaTitles = form.agendaTitles.map((title) => title.trim()).filter(Boolean);
  const agendaCount = agendaTitles.length + form.agendaTaskIds.length;
  const invalidPeriod = Boolean(form.candidateStartAt && form.candidateEndAt && form.candidateStartAt >= form.candidateEndAt);

  const toggle = (key: 'participantIds' | 'agendaTaskIds', id: number) =>
    setForm((current) => ({ ...current, [key]: current[key].includes(id) ? current[key].filter((value) => value !== id) : [...current[key], id] }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!form.title.trim()) { setError(new Error('회의 제목을 입력해 주세요.')); return; }
    if (invalidPeriod) { setError(new Error('후보 기간의 끝은 시작보다 뒤여야 합니다.')); return; }
    setSaving(true);
    setError(undefined);
    try {
      const created = await createMeeting(workspaceId, {
        ...form,
        title: form.title.trim(),
        agendaTitles,
        candidateStartAt: `${form.candidateStartAt}:00`,
        candidateEndAt: `${form.candidateEndAt}:00`,
      });
      navigate(`/workspaces/${workspaceId}/meetings/${created.meetingId}`);
    } catch (err) {
      setError(err as Error);
      setSaving(false);
    }
  };

  return (
    <MeetingFormShell title="새 회의 만들기" description="무엇을 이야기할지 정하고, 함께할 시간을 찾아보세요."
      saving={saving} onSubmit={submit} onClose={onClose} error={error}
      submitDisabled={!form.title.trim() || members.loading || Boolean(members.error) || invalidPeriod}
      submitLabel="회의 만들기" savingLabel="회의 만드는 중…"
      footerHint={<><span className="font-semibold text-[var(--primary)]">회의 준비</span><MeetingFormIcon name="arrow" className="h-3 w-3" />시간 조율<MeetingFormIcon name="arrow" className="h-3 w-3" />일정 확정</>}
      sidebar={<>
        <MeetingParticipantsField members={members} value={form.participantIds} lockedUserId={user.userId}
          onChange={(participantIds) => setForm((current) => ({ ...current, participantIds }))} />
        <MeetingFormSummary value={form} agendaCount={agendaCount} startAt={form.candidateStartAt} endAt={form.candidateEndAt} />
        <p className="m-0 mt-3 px-1 text-[11px] leading-5 text-[var(--muted)]">회의를 만들면 참여자들이 가능한 시간을 입력하고, 추천 시간 중에서 일정을 확정할 수 있어요.</p>
      </>}>
      <MeetingInformationFields value={form} onChange={(patch) => setForm((current) => ({ ...current, ...patch }))} />
      <MeetingCandidatePeriodFields value={form} saving={saving} invalid={invalidPeriod}
        onChange={(patch) => setForm((current) => ({ ...current, ...patch }))} />
          <fieldset className="m-0 min-w-0 rounded-none border-0 border-t border-[var(--border-soft)] p-0 pt-5">
            <legend className="float-left mb-4 flex w-full items-start gap-2.5 p-0">
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-lg bg-[var(--primary-soft)] text-[11px] font-bold text-[var(--primary)]">03</span>
              <span className="text-sm leading-6 font-bold">회의 안건 <span className="ml-1 text-xs font-medium text-[var(--muted)]">선택</span></span>
              <span className="ml-auto rounded-md bg-[var(--primary-soft)] px-2 py-0.5 text-[11px] font-bold text-[var(--primary)]">{agendaCount}개</span>
            </legend>
            <div className="clear-both">
              <p className="m-0 mb-4 text-xs leading-5 text-[var(--muted)]">논의할 내용을 직접 적거나, 기존 팀 작업을 안건으로 연결해 보세요.</p>
              <div className="space-y-2.5" role="group" aria-label="직접 입력 안건">
                <p className="m-0 text-xs font-semibold text-[var(--text)]">새 안건 직접 입력</p>
                {form.agendaTitles.map((title, index) => (
                  <div key={index} className="flex items-center gap-2">
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[var(--primary-soft)] text-[11px] font-bold text-[var(--primary)]">{index + 1}</span>
                    <input aria-label={`직접 입력 안건 ${index + 1}`} className={`${FIELD} py-2.5 text-xs`} maxLength={200}
                      placeholder={index === 0 ? '예: 발표 순서와 역할 분담 논의' : '함께 논의할 내용을 입력하세요'} value={title}
                      autoFocus={index > 0}
                      onChange={(event) => setForm((current) => ({ ...current, agendaTitles: current.agendaTitles.map((value, row) => row === index ? event.target.value : value) }))}
                      onKeyDown={(event) => {
                        if (event.key !== 'Enter') return;
                        event.preventDefault();
                        if (title.trim() && index === form.agendaTitles.length - 1 && form.agendaTitles.length < 50) {
                          setForm((current) => ({ ...current, agendaTitles: [...current.agendaTitles, ''] }));
                        }
                      }} />
                    <button type="button" aria-label={`직접 입력 안건 ${index + 1} 삭제`}
                      className="flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-[var(--muted)] transition hover:bg-[var(--danger-soft)] hover:text-[var(--danger)]"
                      onClick={() => setForm((current) => ({ ...current, agendaTitles: current.agendaTitles.length === 1 ? [''] : current.agendaTitles.filter((_, row) => row !== index) }))}>
                      <MeetingFormIcon name="close" />
                    </button>
                  </div>
                ))}
                <button type="button" disabled={form.agendaTitles.length >= 50}
                  className="cursor-pointer rounded-lg border-0 bg-[var(--primary-soft)] px-3 py-2 text-[11px] font-bold text-[var(--primary)] transition hover:brightness-95 disabled:cursor-not-allowed disabled:opacity-40"
                  onClick={() => setForm((current) => ({ ...current, agendaTitles: [...current.agendaTitles, ''] }))}>+ 안건 입력칸 추가</button>
              </div>
              <div className="mb-3 mt-5 flex items-center gap-2 border-t border-[var(--border-soft)] pt-4">
                <NavIcon name="tasks" className="h-3.5 w-3.5 text-[var(--muted)]" />
                <span className="text-xs font-semibold">기존 팀 작업 연결</span>
                <span className="ml-auto text-[11px] text-[var(--muted)]">{form.agendaTaskIds.length}개 선택</span>
              </div>
              {tasks.loading && <Loading />}
              {tasks.error && <ErrorBox error={tasks.error} onRetry={tasks.reload} />}
              {tasks.data?.length === 0 && <p className="m-0 rounded-xl border border-dashed border-[var(--border)] p-5 text-center text-xs leading-5 text-[var(--muted)]">연결할 팀 작업이 없어요. 위에서 새 안건을 직접 입력할 수 있어요.</p>}
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {tasks.data?.map((task) => {
                  const selected = form.agendaTaskIds.includes(task.taskId);
                  return <label key={task.taskId} className={`relative flex min-w-0 cursor-pointer items-start gap-2.5 rounded-xl border p-3 transition focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-[var(--primary)] ${selected ? SELECTED : UNSELECTED}`}>
                    <input className="sr-only" type="checkbox" checked={selected} onChange={() => toggle('agendaTaskIds', task.taskId)} />
                    <span className={`mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-[5px] border ${selected ? 'border-[var(--primary)] bg-[var(--primary)] text-white' : 'border-[var(--border)] bg-[var(--surface)]'}`}>
                      {selected && <MeetingFormIcon name="check" className="h-3 w-3" />}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-xs leading-5 font-semibold text-[var(--text)]" title={task.title}>{task.title}</span>
                      <span className="mt-1 block truncate text-[11px] text-[var(--muted)]">진행률 {Math.round(task.progress)}%{task.dueDate ? ` · ${formatDate(task.dueDate)} 마감` : ' · 마감 미정'}</span>
                    </span>
                  </label>;
                })}
              </div>
            </div>
          </fieldset>
    </MeetingFormShell>
  );
}
