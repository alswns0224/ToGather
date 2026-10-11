import { useEffect, useRef, useState, type FormEvent } from 'react';
import { NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router';
import { getWorkspace, getWorkspaces } from '../pages/workspace/api';
import { logout } from '../pages/auth/api';
import { http, ws } from '../api/client';
import { useAsync } from '../hooks/useAsync';
import { useLoginUser } from '../components/RequireAuth';
import { ErrorBox, Loading } from '../components/Status';
import Icon, { type IconName } from '../components/Icon';
import Avatar, { workspaceTone } from '../components/Avatar';

type MenuItem = { to: string; label: string; icon: IconName; badge?: 'analyses' | 'suggestions' };

/** 워크스페이스 메뉴: 현재 워크스페이스의 데이터만 보여준다. */
const WORKSPACE_MENU: MenuItem[] = [
  { to: 'tasks', label: '업무', icon: 'task' },
  { to: 'slack/analyses', label: '논의 정리', icon: 'spark', badge: 'analyses' },
  { to: 'meetings', label: '회의', icon: 'users' },
  { to: 'slack', label: '대화', icon: 'chat' },
  { to: 'notion', label: '문서', icon: 'doc' },
  { to: 'drive', label: '파일', icon: 'folder' },
  { to: 'calendar', label: '일정', icon: 'calendar' },
  { to: 'search', label: '통합 검색', icon: 'search' },
];

/** 개인 메뉴: 내 메시지·메일에서 찾은 후보 (워크스페이스별로 연결한 Slack·Calendar를 쓴다) */
const PERSONAL_MENU: MenuItem[] = [{ to: 'assistant', label: 'AI 보조', icon: 'spark', badge: 'suggestions' }];

const UTILITY_MENU: MenuItem[] = [
  { to: 'members', label: '워크스페이스 설정', icon: 'users' },
  { to: 'integrations', label: '서비스 연동', icon: 'link' },
];

/** 대화(Slack) 화면은 3단 레이아웃이라 가운데 정렬 여백 없이 화면을 꽉 채운다. */
const FULL_BLEED = /\/slack\/?$/;

export default function WorkspaceLayout() {
  const { workspaceId = '' } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const user = useLoginUser();
  // 멤버가 아니면 403이 와서 여기서 막힌다.
  const workspace = useAsync(() => getWorkspace(workspaceId), [workspaceId]);
  const badges = useBadges(workspaceId, location.pathname);

  const current = [...WORKSPACE_MENU, ...PERSONAL_MENU, ...UTILITY_MENU]
    .filter((m) => location.pathname.startsWith(`/workspaces/${workspaceId}/${m.to}`))
    .sort((a, b) => b.to.length - a.to.length)[0];
  const isPersonal = current && PERSONAL_MENU.includes(current);
  const fullBleed = FULL_BLEED.test(location.pathname);

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <WorkspaceSwitcher workspaceId={workspaceId} name={workspace.data?.name} memberCount={workspace.data?.memberCount} />
        <nav className="primary-nav">
          {WORKSPACE_MENU.map((m) => (
            <MenuLink key={m.to} item={m} workspaceId={workspaceId} active={current === m} badge={badges[m.badge ?? '']} />
          ))}
        </nav>
        <div className="personal-nav">
          <div className="personal-nav-label">개인</div>
          {PERSONAL_MENU.map((m) => (
            <MenuLink key={m.to} item={m} workspaceId={workspaceId} active={current === m} badge={badges[m.badge ?? '']} />
          ))}
        </div>
        <div className="app-sidebar-bottom">
          {UTILITY_MENU.map((m) => (
            <MenuLink key={m.to} item={m} workspaceId={workspaceId} active={current === m} />
          ))}
          <ProfileButton
            name={user.name}
            email={user.email}
            image={user.profileImage}
            onLogout={async () => {
              await logout();
              navigate('/login', { replace: true });
            }}
            onWorkspaces={() => navigate('/workspaces')}
          />
        </div>
      </aside>

      <div className="app-main">
        <header className="app-topbar">
          <div className="page-title">
            {current?.label ?? ''}
            {current && <small> · {isPersonal ? '개인' : (workspace.data?.name ?? '')}</small>}
          </div>
          <div className="top-actions">
            <SearchBox workspaceId={workspaceId} />
            <Avatar name={user.name} image={user.profileImage} toneKey={user.userId} />
          </div>
        </header>
        <main className={fullBleed ? undefined : 'app-content'}>
          {workspace.loading && !workspace.data && <Loading />}
          {workspace.error && <ErrorBox error={workspace.error} onRetry={workspace.reload} />}
          {workspace.data && <Outlet />}
        </main>
      </div>
    </div>
  );
}

function MenuLink({
  item,
  workspaceId,
  active,
  badge,
}: {
  item: MenuItem;
  workspaceId: string;
  active: boolean;
  badge?: number;
}) {
  return (
    <NavLink to={`/workspaces/${workspaceId}/${item.to}`} className={`nav-item${active ? ' active' : ''}`} end>
      <Icon name={item.icon} size={17} />
      <span>{item.label}</span>
      {!!badge && <span className="nav-badge">{badge > 99 ? '99+' : badge}</span>}
    </NavLink>
  );
}

/** 사이드바 상단: 현재 워크스페이스와 전환 드롭다운 */
function WorkspaceSwitcher({
  workspaceId,
  name,
  memberCount,
}: {
  workspaceId: string;
  name?: string;
  memberCount?: number;
}) {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const list = useAsync(() => (open ? getWorkspaces() : Promise.resolve(null)), [open]);
  const ref = useOutsideClose<HTMLDivElement>(open, () => setOpen(false));

  return (
    <div className="workspace-switcher" ref={ref}>
      <button type="button" className="workspace-switcher-button" onClick={() => setOpen((o) => !o)}>
        <span className={`workspace-switcher-icon ${workspaceTone(workspaceId)}`}>
          <Icon name="users" size={17} />
        </span>
        <span>
          <strong>{name ?? ' '}</strong>
          <small>{memberCount != null ? `멤버 ${memberCount}명` : ' '}</small>
        </span>
        <span className={`switcher-caret${open ? ' open' : ''}`}>
          <Icon name="chevron-down" size={15} />
        </span>
      </button>
      {open && (
        <div className="workspace-dropdown">
          <div className="workspace-dropdown-label">내 워크스페이스</div>
          {list.loading && <div className="workspace-dropdown-label">불러오는 중...</div>}
          {list.error && <div className="workspace-dropdown-label">목록을 불러오지 못했습니다.</div>}
          {list.data?.map((w) => (
            <button
              type="button"
              key={w.workspaceId}
              className={String(w.workspaceId) === workspaceId ? 'active' : ''}
              onClick={() => {
                setOpen(false);
                navigate(`/workspaces/${w.workspaceId}`);
              }}
            >
              <span className={`workspace-option-icon ${workspaceTone(w.workspaceId)}`}>{w.name.slice(0, 1)}</span>
              <span>
                <strong>{w.name}</strong>
                <small>멤버 {w.memberCount}명</small>
              </span>
              {String(w.workspaceId) === workspaceId && <Icon name="check" size={15} />}
            </button>
          ))}
          <div className="workspace-dropdown-divider" />
          <button type="button" className="create-workspace-option" onClick={() => navigate('/workspaces?create=1')}>
            <span>
              <Icon name="plus" size={14} />
            </span>
            새 워크스페이스 만들기
          </button>
        </div>
      )}
    </div>
  );
}

function ProfileButton({
  name,
  email,
  image,
  onLogout,
  onWorkspaces,
}: {
  name: string;
  email: string;
  image?: string | null;
  onLogout: () => void;
  onWorkspaces: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose<HTMLDivElement>(open, () => setOpen(false));
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      {open && (
        <div className="profile-menu">
          <button type="button" onClick={onWorkspaces}>
            <Icon name="users" size={15} />
            워크스페이스 목록
          </button>
          <button type="button" onClick={onLogout}>
            <Icon name="logout" size={15} />
            로그아웃
          </button>
        </div>
      )}
      <button type="button" className="profile" onClick={() => setOpen((o) => !o)}>
        <Avatar name={name} image={image} toneKey={email} />
        <span className="profile-copy">
          <strong>{name}</strong>
          <span>{email}</span>
        </span>
        <span className="more">•••</span>
      </button>
    </div>
  );
}

/** 상단 검색창: 엔터를 누르면 통합 검색 화면으로 이동한다. */
function SearchBox({ workspaceId }: { workspaceId: string }) {
  const [query, setQuery] = useState('');
  const navigate = useNavigate();
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;
    navigate(`/workspaces/${workspaceId}/search?q=${encodeURIComponent(query.trim())}`);
  };
  return (
    <form onSubmit={submit}>
      <label className="searchbox">
        <Icon name="search" size={16} />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="검색" aria-label="통합 검색" />
      </label>
    </form>
  );
}

/** 사이드바 배지: 검토 대기 논의 정리 수, AI 보조 후보 수. 실패해도 화면은 그대로 둔다. */
function useBadges(workspaceId: string, pathname: string) {
  const [badges, setBadges] = useState<Record<string, number>>({});
  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      http.get<unknown[]>(ws(workspaceId, '/analyses')),
      http.get<unknown[]>(ws(workspaceId, '/assistant/suggestions')),
    ]).then(([analyses, suggestions]) => {
      if (cancelled) return;
      setBadges({
        analyses: analyses.status === 'fulfilled' ? analyses.value.length : 0,
        suggestions: suggestions.status === 'fulfilled' ? suggestions.value.length : 0,
      });
    });
    return () => {
      cancelled = true;
    };
    // 화면을 옮길 때마다 다시 센다. (확정·승인하면 줄어든다)
  }, [workspaceId, pathname]);
  return badges;
}

function useOutsideClose<T extends HTMLElement>(open: boolean, close: () => void) {
  const ref = useRef<T>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close();
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open, close]);
  return ref;
}
