// 워크스페이스 홈 대시보드 — 작업·최근 논의와 회의 | 마감·연동 상태
import { Link, useNavigate, useParams } from 'react-router';
import { useAsync } from '../../hooks/useAsync';
import PageHeader from '../../components/PageHeader';
import ServiceLogo, { PROVIDER_LOGO } from '../../components/ServiceLogo';
import NavIcon, { type IconName } from '../../layouts/NavIcon';
import { getWorkspace, getMembers } from '../workspace/api';
import { getTaskProgress, getTasks } from '../task/api';
import { getDriveFiles } from '../drive/api';
import { getPages } from '../notion/api';
import { getChannels } from '../slack/api';
import { getPendingAnalyses } from '../analysis/api';
import { getMeetings } from '../meeting/api';
import { candidateWhen, getMyTodoList, getSuggestions } from '../assistant/api';
import { getIntegrations, PROVIDER_LABELS, type ConnectionStatus } from '../integration/api';
import { formatDate, formatDateTime } from '../../utils/date';

/** 실패해도 화면은 그린다 (해당 칸만 비움) */
const safe = <T,>(p: Promise<T>) => p.catch(() => null);

const seoulDate = () => {
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)!.value;
  return `${part('year')}-${part('month')}-${part('day')}`;
};

const sevenDaysAfter = (day: string) => {
  const date = new Date(`${day}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 7);
  return date.toISOString().slice(0, 10);
};

const CONN_BADGE: Record<ConnectionStatus, [string, string]> = {
  CONNECTED: ['연결됨', 'badge-success'],
  TARGET_NOT_SELECTED: ['대상 선택', 'badge-primary'],
  REAUTH_REQUIRED: ['재인증', 'badge-warning'],
  DISCONNECTED: ['연결 전', ''],
};

function Kpi({
  label,
  icon,
  value,
  sub,
  subClass = '',
  to,
}: {
  label: string;
  icon: IconName;
  value: React.ReactNode;
  sub: string;
  subClass?: string;
  to: string;
}) {
  return (
    <Link to={to} className="card home-kpi">
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-[var(--muted)]">{label}</span>
        <span className="home-kpi-icon">
          <NavIcon name={icon} />
        </span>
      </div>
      <div className="text-[30px] leading-tight font-extrabold text-[var(--text)]">{value}</div>
      <div className={`text-[13px] ${subClass || 'text-[var(--muted)]'}`}>{sub}</div>
    </Link>
  );
}

export default function HomePage() {
  const { workspaceId = '' } = useParams();
  const navigate = useNavigate();
  const base = `/workspaces/${workspaceId}`;
  const dateKey = seoulDate();
  const dueBefore = sevenDaysAfter(dateKey);

  const data = useAsync(async () => {
    const [ws, members, tasks, progress, dueTasks, drive, notion, channels, analyses, meetings, suggestions, integrations, todos] = await Promise.all([
      safe(getWorkspace(workspaceId)),
      safe(getMembers(workspaceId)),
      safe(getTasks(workspaceId, 0, 5)),
      safe(getTaskProgress(workspaceId)),
      safe(getTasks(workspaceId, 0, 5, { status: 'IN_PROGRESS', dueBefore, sort: 'DUE_DATE' })),
      safe(getDriveFiles(workspaceId)),
      safe(getPages(workspaceId)),
      safe(getChannels(workspaceId)),
      safe(getPendingAnalyses(workspaceId)),
      safe(getMeetings(workspaceId)),
      safe(getSuggestions(workspaceId)),
      safe(getIntegrations(workspaceId)),
      safe(getMyTodoList(workspaceId)),
    ]);
    return { ws, members, tasks, progress, dueTasks, drive, notion, channels, analyses, meetings, suggestions, integrations, todos };
  }, [workspaceId, dateKey]);

  const d = data.data;
  const taskItems = d?.tasks?.items ?? [];
  const overall = d?.progress?.overall;
  const now = Date.now();
  const upcomingMeetings = (d?.meetings ?? []).filter(
    (m) => m.status !== 'CANCELED' && (m.startAt ? new Date(m.startAt).getTime() >= now : new Date(m.candidateEndAt).getTime() >= now),
  );
  const pendingSuggestions = (d?.suggestions ?? []).filter((s) => s.status === 'PENDING');
  const memberName = (id: number | null) => d?.members?.find((m) => m.userId === id)?.name ?? '미지정';

  // 최근 AI 정리 · 회의 일정: 검토할 일정 후보, 검토 대기 논의 정리, 다가오는 회의
  // 이번 주 마감: 끝나지 않은 팀 작업 + 개인 할 일 중 마감이 7일 안이거나 이미 지난 것
  const today = d?.progress?.asOfDate ?? dateKey;
  const dueThisWeek = [
    ...(d?.dueTasks?.items ?? [])
      .map((t) => ({
        key: `t-${t.taskId}`,
        title: t.title,
        due: t.dueDate!,
        kind: '팀 작업',
        who: memberName(t.assigneeId),
        to: `${base}/tasks/${t.taskId}`,
      })),
    ...(d?.todos?.personalTasks ?? [])
      .filter((t) => t.status !== 'DONE' && t.dueDate && t.dueDate < dueBefore)
      .map((t) => ({
        key: `p-${t.personalTaskId}`,
        title: t.title,
        due: t.dueDate!,
        kind: '개인 할 일',
        who: '나만 보기',
        to: `${base}/assistant`,
      })),
  ].sort((a, b) => a.due.localeCompare(b.due));

  const recent = [
    ...pendingSuggestions
      .filter((s) => s.type === 'SCHEDULE')
      .map((s) => ({
        key: `s-${s.suggestionId}`,
        title: s.title,
        sub: candidateWhen(s.detectedDatetime, { date: formatDate, dateTime: formatDateTime }),
        tag: `${s.sourceProvider === 'GMAIL' ? 'Gmail' : 'Slack'}${s.sourceAuthor ? ` · ${s.sourceAuthor}` : ''}`,
        to: `${base}/assistant`,
      })),
    ...(d?.analyses ?? []).map((a) => ({
      key: `a-${a.analysisId}`,
      title: a.threadTitle ?? '논의 정리',
      sub: `검토 대기 · 항목 ${a.itemCount}개`,
      tag: 'AI 논의 정리',
      to: `${base}/slack/analysis/${a.analysisId}`,
    })),
    ...upcomingMeetings.map((m) => ({
      key: `m-${m.meetingId}`,
      title: m.title,
      sub: m.startAt ? formatDateTime(m.startAt) : `후보 기간 ${formatDate(m.candidateStartAt)} ~ ${formatDate(m.candidateEndAt)}`,
      tag: m.status === 'CONFIRMED' ? '확정된 회의' : '일정 조율 중',
      to: `${base}/meetings/${m.meetingId}`,
    })),
  ].slice(0, 6);

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="home" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="대시보드"
        description="흩어진 대화·문서·파일·일정을 한눈에 보고 바로 작업으로 이어가세요."
        actions={
          <>
            <Link to={`${base}/search`} className="btn">
              <NavIcon name="search" /> 통합 검색
            </Link>
            <button type="button" className="btn btn-primary" onClick={() => navigate(`${base}/tasks`)}>
              + 새 팀 작업
            </button>
          </>
        }
      />

      {data.loading && !d && <p className="text-sm text-[var(--muted)]">불러오는 중...</p>}

      {d && (
        <>
          <div className="task-kpis">
            <Kpi
              label="진행 중 팀 작업"
              icon="tasks"
              to={`${base}/tasks`}
              value={
                <>
                  {overall?.inProgressCount ?? '—'}건{' '}
                  <span className="text-base font-bold text-[var(--success)]">/ 완료 {overall?.doneCount ?? '—'}</span>
                </>
              }
              sub={`전체 ${overall?.totalCount ?? d.tasks?.total ?? '—'}건 · 클릭하여 관리`}
            />
            <Kpi
              label="연결된 Drive 자료"
              icon="drive"
              to={`${base}/drive`}
              value={`${d.drive?.length ?? 0}개`}
              sub={`Notion 문서 ${d.notion?.length ?? 0}건 · Slack 채널 ${d.channels?.length ?? 0}개`}
            />
            <Kpi
              label="AI 논의 정리"
              icon="analyses"
              to={`${base}/slack/analyses`}
              value={`${d.analyses?.length ?? 0}건`}
              sub="검토 대기 · 확정하면 팀 작업으로 이어져요"
              subClass="text-[var(--primary)]"
            />
            <Kpi
              label="예정 팀 회의"
              icon="meetings"
              to={`${base}/meetings`}
              value={`${upcomingMeetings.length}건`}
              sub={`AI 업무 보조 후보 ${pendingSuggestions.length}건 대기`}
            />
          </div>

          <div className="home-board">
            <div className="flex min-w-0 flex-col gap-4">
              {/* 팀 작업 현황 */}
              <div className="card home-card">
                <div className="flex items-center justify-between">
                  <strong className="text-[17px]">팀 작업 & 산출물 진행 현황</strong>
                  <Link to={`${base}/tasks`} className="text-sm font-semibold text-[var(--muted)] no-underline">
                    전체 보기 →
                  </Link>
                </div>
                {overall ? (
                  <div className="rounded-xl bg-[var(--surface-2)] p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
                      <strong>팀 전체 평균 진행률 {overall.progress}%</strong>
                      <span className="text-xs text-[var(--muted)]">정상 {overall.normalCount} · 주의 {overall.warningCount} · 위험 {overall.dangerCount}</span>
                    </div>
                    <div className="mt-2 h-2 overflow-hidden rounded-full bg-[var(--surface)]">
                      <div className="h-full rounded-full bg-[image:var(--brand-grad)]" style={{ width: `${overall.progress}%` }} />
                    </div>
                  </div>
                ) : <div className="alert alert-error"><span>팀 작업 집계를 불러오지 못했습니다.</span>
                  <button type="button" className="btn btn-small" onClick={data.reload}>다시 시도</button></div>}
                {d.tasks === null && <p className="m-0 text-sm text-[var(--danger)]">최근 팀 작업을 불러오지 못했습니다.</p>}
                {d.tasks && taskItems.length === 0 && <p className="m-0 text-sm text-[var(--muted)]">아직 팀 작업이 없어요.</p>}
                {taskItems.slice(0, 5).map((t) => {
                  const done = t.status === 'DONE';
                  const name = memberName(t.assigneeId);
                  return (
                    <Link key={t.taskId} to={`${base}/tasks/${t.taskId}`} className="home-task">
                      <div className="flex items-center gap-3">
                        <span className="tg-avatar">{name.slice(0, 1)}</span>
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-base font-bold text-[var(--text)]">{t.title}</div>
                          <div className="text-[13px] text-[var(--muted)]">
                            {name} · 마감 {t.dueDate ?? '미정'}
                          </div>
                        </div>
                        <span className={`badge ${done ? 'badge-success' : 'badge-primary'}`}>{done ? '완료' : '진행 중'}</span>
                      </div>
                      <div className="mt-3 flex items-center gap-3">
                        <div className="h-2 flex-1 overflow-hidden rounded-full bg-[var(--surface-2)]">
                          <div className="h-full rounded-full bg-[image:var(--brand-grad)]" style={{ width: `${t.progress}%` }} />
                        </div>
                        <span className="w-11 text-right text-sm font-bold text-[var(--text)]">{t.progress}%</span>
                      </div>
                    </Link>
                  );
                })}
              </div>

              {/* 최근 AI 정리 · 회의 */}
              <div className="card home-card">
                <div className="flex items-center justify-between">
                  <strong className="text-[17px]">최근 AI 논의 & 회의 일정</strong>
                  <Link to={`${base}/slack/analyses`} className="text-sm font-semibold text-[var(--muted)] no-underline">
                    분석 보기 →
                  </Link>
                </div>
                {recent.length === 0 && (
                  <p className="m-0 text-sm text-[var(--muted)]">검토할 일정 후보, 논의 정리, 다가오는 회의가 없어요.</p>
                )}
                {recent.map((r) => (
                  <Link key={r.key} to={r.to} className="home-recent">
                    <div className="flex items-start justify-between gap-2">
                      <strong className="text-[15px] text-[var(--text)]">{r.title}</strong>
                      <span className="badge badge-primary flex-shrink-0">{r.tag}</span>
                    </div>
                    <div className="mt-1 text-sm text-[var(--muted)]">{r.sub}</div>
                  </Link>
                ))}
              </div>
            </div>

            <div className="flex min-w-0 flex-col gap-4">
              {/* 이번 주 마감 */}
              <div className="card home-card">
                <div className="flex items-center justify-between">
                  <strong className="text-[17px]">이번 주 마감</strong>
                  <span className="text-xs text-[var(--muted)]">팀 작업 · 개인 할 일</span>
                </div>
                {d.dueTasks === null && <div className="alert alert-error"><span>팀 작업 마감 목록을 불러오지 못했습니다.</span>
                  <button type="button" className="btn btn-small" onClick={data.reload}>다시 시도</button></div>}
                {d.dueTasks && dueThisWeek.length === 0 && (
                  <p className="m-0 text-sm text-[var(--muted)]">7일 안에 마감인 팀 작업이나 개인 할 일이 없어요.</p>
                )}
                {dueThisWeek.map((item) => {
                  const overdue = item.due < today;
                  return (
                    <Link key={item.key} to={item.to} className="home-conn no-underline">
                      <span className={`badge ${item.kind === '팀 작업' ? 'badge-primary' : 'badge-success'} flex-shrink-0`}>
                        {item.kind}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[15px] font-bold text-[var(--text)]">{item.title}</span>
                        <span className="text-xs text-[var(--muted)]">{item.who}</span>
                      </span>
                      <span className={`flex-shrink-0 text-xs font-bold ${overdue ? 'text-[var(--danger)]' : 'text-[var(--muted)]'}`}>
                        {overdue ? '지남 · ' : ''}
                        {formatDate(item.due)}
                      </span>
                    </Link>
                  );
                })}
                {d.dueTasks && d.dueTasks.total > d.dueTasks.items.length && (
                  <Link to={`${base}/tasks?status=IN_PROGRESS&dueBefore=${dueBefore}&sort=DUE_DATE`} className="text-xs">
                    마감 대상 팀 작업 {d.dueTasks.total}건 전체 보기 →
                  </Link>
                )}
              </div>

              {/* 연동 상태 */}
              <div className="card home-card">
                <div className="flex items-center justify-between">
                  <strong className="text-[17px]">외부 서비스 연동 상태</strong>
                  <Link to={`${base}/integrations`} className="text-sm font-semibold text-[var(--muted)] no-underline">
                    관리 →
                  </Link>
                </div>
                {(d.integrations ?? []).map((item) => {
                  const conn = item.personal ? item.connections.find((c) => c.mine) : item.connections[0];
                  const [text, cls] = CONN_BADGE[conn?.status ?? 'DISCONNECTED'];
                  const targets = conn?.targets.map((t) => t.name ?? t.id).join(', ');
                  return (
                    <div key={item.provider} className="home-conn">
                      <span className="inline-flex h-5 w-5 flex-shrink-0">
                        <ServiceLogo service={PROVIDER_LOGO[item.provider]} />
                      </span>
                      <span className="min-w-0 flex-1 truncate text-[15px]">
                        <strong>{PROVIDER_LABELS[item.provider].name}</strong>
                        <span className="text-[13px] text-[var(--muted)]"> · {targets || (conn ? '대상 미선택' : '미연결')}</span>
                      </span>
                      <span className={`badge ${cls}`}>{text}</span>
                    </div>
                  );
                })}
              </div>

            </div>
          </div>
        </>
      )}
    </section>
  );
}
