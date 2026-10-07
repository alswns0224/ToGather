import { createBrowserRouter, Navigate } from 'react-router';
import WorkspaceLayout from './layouts/WorkspaceLayout';
import LoginPage from './pages/auth/LoginPage';
import WorkspaceListPage from './pages/workspace/WorkspaceListPage';
import InviteJoinPage from './pages/workspace/InviteJoinPage';
import MembersPage from './pages/workspace/MembersPage';
import IntegrationsPage from './pages/integration/IntegrationsPage';
import SlackPage from './pages/slack/SlackPage';
import NotionPage from './pages/notion/NotionPage';
import DrivePage from './pages/drive/DrivePage';
import CalendarPage from './pages/calendar/CalendarPage';
import AnalysisPage from './pages/analysis/AnalysisPage';
import TaskListPage from './pages/task/TaskListPage';
import TaskContextPage from './pages/task/TaskContextPage';
import MeetingListPage from './pages/meeting/MeetingListPage';
import SearchPage from './pages/search/SearchPage';
import AssistantPage from './pages/assistant/AssistantPage';

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/workspaces" replace /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/workspaces', element: <WorkspaceListPage /> },
  { path: '/invite/:token', element: <InviteJoinPage /> },
  {
    path: '/workspaces/:workspaceId',
    element: <WorkspaceLayout />,
    children: [
      { index: true, element: <Navigate to="tasks" replace /> },
      { path: 'tasks', element: <TaskListPage /> },
      { path: 'tasks/:taskId', element: <TaskContextPage /> },
      { path: 'slack', element: <SlackPage /> },
      { path: 'slack/analysis/:analysisId', element: <AnalysisPage /> },
      { path: 'notion', element: <NotionPage /> },
      { path: 'drive', element: <DrivePage /> },
      { path: 'calendar', element: <CalendarPage /> },
      { path: 'meetings', element: <MeetingListPage /> },
      { path: 'search', element: <SearchPage /> },
      { path: 'assistant', element: <AssistantPage /> },
      { path: 'integrations', element: <IntegrationsPage /> },
      { path: 'members', element: <MembersPage /> },
    ],
  },
]);
