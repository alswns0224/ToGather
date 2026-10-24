// 워크스페이스 공통 레이아웃: 왼쪽 메뉴 + 상단바 (docs/ui-prototype 시안)
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router';
import { getWorkspace } from '../pages/workspace/api';
import { logout } from '../pages/auth/api';
import { useAsync } from '../hooks/useAsync';
import { useLoginUser } from '../components/RequireAuth';
import { ErrorBox, Loading } from '../components/Status';
import ThemeToggle from '../components/ThemeToggle';
import NavIcon, { type IconName } from './NavIcon';

/** also: 이 경로로 시작하는 하위 화면에서도 메뉴를 켠다 (예: 논의 정리 상세 slack/analysis/7) */
const MENU: { to: string; label: string; icon: IconName; also?: string }[] = [
  { to: 'search', label: '통합 검색', icon: 'search' },
  { to: 'slack', label: 'Slack', icon: 'chat' },
  { to: 'slack/analyses', label: '논의 정리', icon: 'sparkle', also: 'slack/analysis/' },
  { to: 'calendar', label: '일정', icon: 'calendar' },
  { to: 'meetings', label: '회의', icon: 'meeting' },
  { to: 'tasks', label: '작업 (Task)', icon: 'tasks' },
  { to: 'drive', label: '자료 (Drive)', icon: 'folder' },
  { to: 'notion', label: '문서 (Notion)', icon: 'document' },
  { to: 'assistant', label: 'AI 업무 보조', icon: 'sparkle' },
];

const SUB_MENU: { to: string; label: string; icon: IconName }[] = [
  { to: 'members', label: '팀원', icon: 'team' },
  { to: 'integrations', label: '연동 관리', icon: 'link' },
];

const navClass = ({ isActive }: { isActive: boolean }) =>
  `group flex w-full items-center gap-3 rounded-lg px-3 py-[7px] text-sm font-medium no-underline transition-all ${
    isActive
      ? 'bg-blue-600 text-white shadow-sm shadow-blue-200 dark:shadow-none'
      : 'text-slate-600 hover:bg-white hover:text-slate-900 hover:shadow-sm dark:text-slate-300 dark:hover:bg-white/5 dark:hover:text-white'
  }`;

function MenuLink({
  workspaceId,
  to,
  label,
  icon,
  also,
}: {
  workspaceId: string;
  to: string;
  label: string;
  icon: IconName;
  also?: string;
}) {
  const { pathname } = useLocation();
  const alsoActive = !!also && pathname.startsWith(`/workspaces/${workspaceId}/${also}`);
  return (
    // 논의 정리(slack/analyses)가 Slack 메뉴까지 켜지지 않도록 end로 정확히 맞춘다.
    <NavLink
      to={`/workspaces/${workspaceId}/${to}`}
      end={to === 'slack'}
      className={({ isActive }) => navClass({ isActive: isActive || alsoActive })}
    >
      {({ isActive }) => (
        <>
          <span className={isActive || alsoActive ? 'text-white' : 'text-slate-400'}>
            <NavIcon name={icon} />
          </span>
          <span className="flex-1 truncate text-left">{label}</span>
        </>
      )}
    </NavLink>
  );
}

export default function WorkspaceLayout() {
  const { workspaceId = '' } = useParams();
  const user = useLoginUser();
  const navigate = useNavigate();
  // 멤버가 아니면 403이 와서 여기서 막힌다.
  const workspace = useAsync(() => getWorkspace(workspaceId), [workspaceId]);
  const wsName = workspace.data?.name ?? '';

  const signOut = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="flex h-screen overflow-hidden text-slate-900 dark:text-slate-100">
      <aside className="flex h-full w-[220px] flex-shrink-0 select-none flex-col border-r border-slate-200 bg-[#F8F9FB] dark:border-white/10 dark:bg-[#0F111A]">
        {/* 로고 */}
        <div className="flex items-center justify-between border-b border-slate-200/80 px-4 pt-[14px] pb-3 dark:border-white/10">
          <Link to="/workspaces" className="flex items-center gap-2 no-underline">
            <span className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-lg bg-blue-600 shadow-sm shadow-blue-300 dark:shadow-none">
              <svg className="h-3.5 w-3.5 text-white" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                <path d="M8 2a6 6 0 1 0 0 12A6 6 0 0 0 8 2ZM5 8.5l1.5-2 2 2 2.5-3.5 1 .75-3.5 5-1.75-1.75L5 8.5Z" />
              </svg>
            </span>
            <span className="text-[16px] font-bold tracking-tight text-slate-900 dark:text-white">ToGather</span>
          </Link>
          <ThemeToggle />
        </div>

        {/* 워크스페이스 */}
        <div className="border-b border-slate-200/80 px-3 py-3 dark:border-white/10">
          <Link
            to="/workspaces"
            title="워크스페이스 목록으로"
            className="flex w-full items-center gap-2.5 rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-slate-800 no-underline shadow-sm transition-all hover:border-blue-300 dark:border-white/10 dark:bg-white/5 dark:text-white"
          >
            <span className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-md bg-blue-600 text-[10px] font-bold text-white">
              {wsName.slice(0, 1)}
            </span>
            <span className="flex-1 truncate text-left text-sm font-semibold">{wsName || ' '}</span>
            <svg className="h-3.5 w-3.5 flex-shrink-0 text-slate-400" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path
                fillRule="evenodd"
                d="M5.22 8.22a.75.75 0 0 1 1.06 0L10 11.94l3.72-3.72a.75.75 0 1 1 1.06 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0L5.22 9.28a.75.75 0 0 1 0-1.06Z"
                clipRule="evenodd"
              />
            </svg>
          </Link>
        </div>

        {/* 메뉴 */}
        <nav className="flex-1 space-y-0.5 overflow-y-auto px-3 py-3">
          {MENU.map((m) => (
            <MenuLink key={m.to} workspaceId={workspaceId} {...m} />
          ))}
          <div className="mt-2 space-y-0.5 border-t border-slate-200/80 pt-3 dark:border-white/10">
            {SUB_MENU.map((m) => (
              <MenuLink key={m.to} workspaceId={workspaceId} {...m} />
            ))}
          </div>
        </nav>

        {/* 사용자 */}
        <div className="border-t border-slate-200 px-3 pt-3 pb-4 dark:border-white/10">
          <div className="flex items-center gap-2.5 px-3 py-2">
            <span className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full bg-blue-600 text-[11px] font-bold text-white">
              {user.name.slice(0, 2)}
            </span>
            <div className="min-w-0 flex-1">
              <p className="m-0 truncate text-xs font-semibold text-slate-800 dark:text-white">{user.name}</p>
              <p className="m-0 truncate text-[10px] text-slate-400">{user.email}</p>
            </div>
            <button
              type="button"
              onClick={signOut}
              title="로그아웃"
              aria-label="로그아웃"
              className="flex-shrink-0 cursor-pointer border-0 bg-transparent p-0 text-slate-400 transition-colors hover:text-red-400"
            >
              <NavIcon name="logout" className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        {/* 상단바 */}
        <header className="flex h-14 flex-shrink-0 items-center justify-between gap-4 border-b border-slate-200/70 px-6 dark:border-white/10">
          <div className="flex min-w-0 items-center gap-2">
            <span className="h-2 w-2 flex-shrink-0 animate-pulse rounded-full bg-emerald-500" />
            <span className="truncate text-xs font-bold">{wsName}</span>
          </div>
          <Link
            to={`/workspaces/${workspaceId}/search`}
            className="flex max-w-md flex-1 items-center justify-between rounded-2xl border border-slate-200/70 bg-slate-50/90 px-4 py-2 text-xs text-slate-500 no-underline transition-all hover:border-indigo-500/60 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-400"
          >
            <span>🔍 통합 검색</span>
          </Link>
          <div className="flex items-center gap-2">
            <Link
              to="/workspaces"
              className="rounded-xl border border-slate-200/70 bg-slate-50/90 px-3 py-1.5 text-xs font-bold text-slate-800 no-underline dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200"
            >
              공간 전환
            </Link>
            <Link
              to={`/workspaces/${workspaceId}/slack/analyses`}
              className="rounded-xl bg-[linear-gradient(135deg,#4F46E5_0%,#7C3AED_55%,#C026D3_100%)] px-3.5 py-1.5 text-xs font-bold text-white no-underline shadow-[0_6px_20px_-4px_rgba(124,58,237,0.45)] transition hover:-translate-y-px hover:brightness-105"
            >
              ✨ AI 논의 정리
            </Link>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-[1200px] p-6">
            {workspace.loading && <Loading />}
            {workspace.error && <ErrorBox error={workspace.error} onRetry={workspace.reload} />}
            {workspace.data && <Outlet />}
          </div>
        </main>
      </div>
    </div>
  );
}
