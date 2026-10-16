import { NavLink, Outlet, useNavigate, useParams } from 'react-router';
import { getWorkspace } from '../pages/workspace/api';
import { logout } from '../pages/auth/api';
import { useAsync } from '../hooks/useAsync';
import { useLoginUser } from '../components/RequireAuth';
import { ErrorBox, Loading } from '../components/Status';

const MENU = [
  { to: 'tasks', label: 'Task' },
  { to: 'slack', label: 'Slack' },
  { to: 'notion', label: 'Notion' },
  { to: 'drive', label: 'Drive' },
  { to: 'calendar', label: '일정' },
  { to: 'meetings', label: '회의' },
  { to: 'search', label: '통합 검색' },
  { to: 'assistant', label: 'AI 업무 보조' },
  { to: 'integrations', label: '연동 관리' },
  { to: 'members', label: '팀원' },
];

export default function WorkspaceLayout() {
  const { workspaceId = '' } = useParams();
  const user = useLoginUser();
  const navigate = useNavigate();
  // 멤버가 아니면 403이 와서 여기서 막힌다.
  const workspace = useAsync(() => getWorkspace(workspaceId), [workspaceId]);

  const signOut = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="layout">
      <nav className="sidebar">
        <NavLink to="/workspaces" end className="small">
          ← 워크스페이스 목록
        </NavLink>
        <strong className="sidebar-title">{workspace.data?.name ?? ' '}</strong>
        {MENU.map((m) => (
          <NavLink key={m.to} to={`/workspaces/${workspaceId}/${m.to}`}>
            {m.label}
          </NavLink>
        ))}
        <span className="spacer" />
        <div className="sidebar-user small">
          <div>{user.name}</div>
          <button className="link-button" onClick={signOut}>
            로그아웃
          </button>
        </div>
      </nav>
      <main className="content">
        {workspace.loading && <Loading />}
        {workspace.error && <ErrorBox error={workspace.error} onRetry={workspace.reload} />}
        {workspace.data && <Outlet />}
      </main>
    </div>
  );
}
