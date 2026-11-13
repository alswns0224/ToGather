// 워크스페이스 공통 레이아웃: 왼쪽 메뉴 + 상단바 (팀원 UI 시안 togather_ui.html의 shell)
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router';
import { getWorkspace } from '../pages/workspace/api';
import { logout } from '../pages/auth/api';
import { useAsync } from '../hooks/useAsync';
import { useLoginUser } from '../components/RequireAuth';
import { ErrorBox, Loading } from '../components/Status';
import ThemeToggle from '../components/ThemeToggle';
import { useTheme } from '../hooks/useTheme';
import NavIcon, { type IconName } from './NavIcon';
import { useNavCounts, type NavCounts } from './useNavCounts';

type Menu = { to: string; label: string; icon: IconName; also?: string };

/** also: 이 경로로 시작하는 하위 화면에서도 메뉴를 켠다 (예: 논의 정리 상세 slack/analysis/7) */
const TOP_MENU: Menu[] = [{ to: 'search', label: '통합 검색', icon: 'search' }];

const MENU_GROUPS: { title: string; items: Menu[] }[] = [
  {
    title: '연결된 자료',
    items: [
      { to: 'slack', label: 'Slack', icon: 'slack' },
      { to: 'notion', label: 'Notion', icon: 'notion' },
      { to: 'drive', label: 'Google Drive', icon: 'drive' },
    ],
  },
  {
    title: '팀의 작업',
    items: [
      { to: 'slack/analyses', label: '논의 정리', icon: 'analyses', also: 'slack/analysis/' },
      { to: 'tasks', label: 'Task', icon: 'tasks' },
      { to: 'assistant', label: 'AI 업무 보조', icon: 'assistant' },
    ],
  },
  {
    title: '일정과 회의',
    items: [
      { to: 'calendar', label: '일정', icon: 'calendar' },
      { to: 'meetings', label: '회의', icon: 'meetings' },
    ],
  },
];

const SUB_MENU: Menu[] = [
  { to: 'members', label: '팀원', icon: 'members' },
  { to: 'integrations', label: '연동 관리', icon: 'integrations' },
];

const ALL_MENUS = [...TOP_MENU, ...MENU_GROUPS.flatMap((g) => g.items), ...SUB_MENU];

/** 상단바에 보여줄 현재 메뉴 이름 (가장 길게 맞는 경로) */
function currentMenuLabel(pathname: string, workspaceId: string) {
  const path = pathname.replace(`/workspaces/${workspaceId}/`, '');
  if (path.startsWith('tasks/')) return 'Task 협업 맥락';
  const match = ALL_MENUS.filter(
    (m) => path === m.to || path.startsWith(`${m.to}/`) || (m.also && path.startsWith(m.also)),
  ).sort((a, b) => b.to.length - a.to.length)[0];
  return match?.label ?? '';
}

const ROLE_TEXT: Record<string, string> = { OWNER: 'OWNER', MEMBER: 'MEMBER' };

function MenuLink({ workspaceId, menu, count }: { workspaceId: string; menu: Menu; count?: number }) {
  const { pathname } = useLocation();
  const alsoActive = !!menu.also && pathname.startsWith(`/workspaces/${workspaceId}/${menu.also}`);
  return (
    // 논의 정리(slack/analyses)가 Slack 메뉴까지 켜지지 않도록 slack은 end로 정확히 맞춘다.
    <NavLink
      to={`/workspaces/${workspaceId}/${menu.to}`}
      end={menu.to === 'slack'}
      className={({ isActive }) => `nav-btn${isActive || alsoActive ? ' active' : ''}`}
    >
      <span className="nav-btn-left">
        <NavIcon name={menu.icon} />
        <span className="truncate">{menu.label}</span>
      </span>
      {count !== undefined && <span className="nav-count">{count}</span>}
    </NavLink>
  );
}

export default function WorkspaceLayout() {
  const { workspaceId = '' } = useParams();
  const user = useLoginUser();
  const { theme, palette } = useTheme();
  const logoSrc =
    palette === 'indigo' ? '/togather-logo-mark.png' : theme === 'dark' ? '/togather-logo-white.png' : '/togather-logo-carrot.png';
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const menuLabel = currentMenuLabel(pathname, workspaceId);
  // 멤버가 아니면 403이 와서 여기서 막힌다.
  const workspace = useAsync(() => getWorkspace(workspaceId), [workspaceId]);
  const ws = workspace.data;
  const counts: NavCounts = { ...useNavCounts(workspaceId, menuLabel), members: ws?.memberCount };

  const signOut = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="flex h-screen overflow-hidden text-[var(--text)]">
      <aside className="tg-sidebar">
        {/* 로고 */}
        <Link to="/workspaces" className="flex items-center gap-[9px] px-1 no-underline">
          <img src={logoSrc} alt="ToGather 로고" className="h-[30px] w-auto" />
          <span className="text-xl font-bold tracking-[-0.02em] text-[var(--text)]">
            <span className="text-[var(--primary)]">To</span>Gather
          </span>
        </Link>

        {/* 워크스페이스 · 통합 검색 */}
        <div className="flex flex-col gap-1.5">
          <Link to="/workspaces" className="tg-ws-card" title="워크스페이스 선택">
            <div className="truncate text-[13px] font-bold">{ws?.name ?? ' '}</div>
            <div className="text-xs text-[var(--muted)]">워크스페이스 선택{ws ? ` · ${ws.memberCount}명` : ''}</div>
          </Link>
          {TOP_MENU.map((m) => (
            <MenuLink key={m.to} workspaceId={workspaceId} menu={m} />
          ))}
        </div>

        {/* 그룹 메뉴 */}
        <nav className="flex min-h-0 flex-1 flex-col gap-[3px] overflow-y-auto">
          {MENU_GROUPS.map((group) => (
            <div key={group.title} className="flex flex-col gap-[3px]">
              <div className="nav-sec">{group.title}</div>
              {group.items.map((m) => (
                <MenuLink key={m.to} workspaceId={workspaceId} menu={m} count={counts[m.to]} />
              ))}
            </div>
          ))}
        </nav>

        {/* 하단: 팀원 · 연동 관리 · 내 정보 */}
        <div className="flex flex-col gap-1 border-t border-[var(--border-soft)] pt-2">
          {SUB_MENU.map((m) => (
            <MenuLink key={m.to} workspaceId={workspaceId} menu={m} count={counts[m.to]} />
          ))}
          <div className="mt-1 flex items-center justify-between gap-2 border-t border-[var(--border-soft)] pt-2.5">
            <div className="flex min-w-0 items-center gap-2">
              <span className="tg-avatar">{user.name.slice(0, 1)}</span>
              <div className="min-w-0">
                <div className="truncate text-[13px] font-bold">{user.name}</div>
                <div className="truncate text-[11px] text-[var(--muted)]">
                  내 프로필{ws ? ` · ${ROLE_TEXT[ws.myRole] ?? ws.myRole}` : ''}
                </div>
              </div>
            </div>
            <button
              type="button"
              onClick={signOut}
              className="h-7 flex-shrink-0 cursor-pointer rounded-[10px] border border-[var(--border)] bg-[var(--surface-2)] px-[9px] text-[11px] font-semibold text-[var(--text)] hover:border-[var(--primary)]"
            >
              로그아웃
            </button>
          </div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        {/* 상단바 */}
        <header className="tg-topbar">
          <div className="flex min-w-0 items-center gap-2">
            <span className="badge badge-success">동기화 정상</span>
            <span className="truncate text-sm font-semibold">{ws?.name}</span>
            {menuLabel && <span className="truncate text-xs text-[var(--muted)]">/ {menuLabel}</span>}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <ThemeToggle />
            <Link
              to="/workspaces"
              className="inline-flex h-9 items-center rounded-[10px] border border-[var(--border)] bg-[var(--surface-2)] px-3.5 text-[13px] font-semibold text-[var(--text)] no-underline hover:border-[var(--primary)]"
            >
              워크스페이스 전환
            </Link>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto">
          <div className="mx-auto flex max-w-[1320px] flex-col gap-4 px-[26px] py-5">
            {workspace.loading && <Loading />}
            {workspace.error && <ErrorBox error={workspace.error} onRetry={workspace.reload} />}
            {workspace.data && <Outlet />}
          </div>
        </main>
      </div>
    </div>
  );
}
