import { NavLink, Outlet, useParams } from 'react-router';

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
  const { workspaceId } = useParams();

  return (
    <div className="layout">
      <nav className="sidebar">
        <NavLink to="/workspaces" end>
          ← 워크스페이스 목록
        </NavLink>
        {MENU.map((m) => (
          <NavLink key={m.to} to={`/workspaces/${workspaceId}/${m.to}`}>
            {m.label}
          </NavLink>
        ))}
      </nav>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
