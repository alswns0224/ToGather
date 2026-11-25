// UI-10 회의 목록 · 생성 (담당: A)
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { getMeetings, type MeetingSummaryResponse } from './api';

import { useAsync } from '../../hooks/useAsync';

import { ErrorBox, Loading } from '../../components/Status';
import { formatDate, formatDateTime } from '../../utils/date';
import PageHeader from '../../components/PageHeader';
import { MUTED } from '../../components/ui';
import NavIcon from '../../layouts/NavIcon';
import MeetingCreateForm from './MeetingCreateForm';

export const STATUS_TEXT = { SCHEDULING: '일정 조율 중', CONFIRMED: '확정', CANCELED: '취소됨' } as const;

function isPast(m: MeetingSummaryResponse) {
  if (m.status === 'CANCELED') return true;
  if (m.status === 'CONFIRMED' && m.startAt) return new Date(m.startAt) < new Date();
  return new Date(m.candidateEndAt) < new Date();
}

export default function MeetingListPage() {
  const { workspaceId = '' } = useParams();
  const meetings = useAsync(() => getMeetings(workspaceId), [workspaceId]);
  const [creating, setCreating] = useState(false);
  const [tab, setTab] = useState<'upcoming' | 'past'>('upcoming');
  const list = (meetings.data ?? []).filter((m) => (tab === 'past' ? isPast(m) : !isPast(m)));

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="meetings" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="회의"
        description="안건을 모으고, 팀이 함께할 시간을 정해요."
        actions={
          <button type="button" className="btn btn-primary" onClick={() => setCreating((v) => !v)}>
            {creating ? '닫기' : '+ 새 회의'}
          </button>
        }
      />

      <div className="tg-underline-tabs">
        {(['upcoming', 'past'] as const).map((t) => (
          <button key={t} type="button" className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>
            {t === 'upcoming' ? '예정된 회의' : '지난 회의'}
          </button>
        ))}
      </div>

      {creating && <MeetingCreateForm workspaceId={workspaceId} onClose={() => setCreating(false)} />}

      {meetings.loading && <Loading />}
      {meetings.error && <ErrorBox error={meetings.error} onRetry={meetings.reload} />}
      {meetings.data && list.length === 0 && (
        <div className="card p-10 text-center">
          <p className={`m-0 text-sm ${MUTED}`}>{tab === 'upcoming' ? '예정된 회의가 없습니다. "+ 새 회의"로 만들어 보세요.' : '지난 회의가 없습니다.'}</p>
        </div>
      )}
      {list.length > 0 && (
        <div className="flex flex-col gap-3">
          {list.map((m) => (
            <Link key={m.meetingId} to={`/workspaces/${workspaceId}/meetings/${m.meetingId}`} className="card meeting-row">
              <span className="meeting-icon">
                <NavIcon name="calendar" />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <strong className="text-[17px] text-[var(--text)]">{m.title}</strong>
                  <span className={`badge ${MEETING_BADGE[m.status]}`}>{STATUS_TEXT[m.status]}</span>
                </div>
                <div className="mt-0.5 text-[13px] text-[var(--muted)]">
                  {m.durationMinutes >= 60 ? `${m.durationMinutes / 60}시간` : `${m.durationMinutes}분`}
                  {m.meetingMode && ` · ${m.meetingMode} 회의`} · 팀원 {m.participantCount}명 ·{' '}
                  {m.status === 'CONFIRMED' && m.startAt
                    ? formatDateTime(m.startAt)
                    : `후보 기간 ${formatDate(m.candidateStartAt)} ~ ${formatDate(m.candidateEndAt)}`}
                </div>
              </div>
              {m.status === 'SCHEDULING' && (
                <span className={`badge ${m.respondedCount >= m.participantCount ? 'badge-success' : ''}`}>
                  {m.respondedCount >= m.participantCount ? '✓ ' : ''}
                  {m.respondedCount}/{m.participantCount}명 응답
                </span>
              )}
              <span className="text-[var(--muted)]">›</span>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

export const MEETING_BADGE = { SCHEDULING: 'badge-warning', CONFIRMED: 'badge-success', CANCELED: '' } as const;
