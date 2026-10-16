import { http, ws } from '../../api/client';

export type Provider = 'SLACK' | 'NOTION' | 'GOOGLE_DRIVE' | 'GOOGLE_CALENDAR' | 'GMAIL';
export type ConnectionStatus = 'TARGET_NOT_SELECTED' | 'CONNECTED' | 'REAUTH_REQUIRED' | 'DISCONNECTED';

export interface TargetItem {
  id: string;
  name: string | null;
}

export interface ConnectionStatusDTO {
  connectionId: number;
  provider: Provider;
  status: ConnectionStatus;
  externalAccountId: string | null;
  /** 화면에 보여줄 계정 이름 (Slack 팀, Notion 워크스페이스, Google 이메일) */
  accountLabel: string | null;
  connectedByUserId: number;
  connectedByName: string | null;
  mine: boolean;
  targets: TargetItem[];
}

export interface IntegrationStatusResponse {
  provider: Provider;
  personal: boolean;
  configured: boolean;
  connections: ConnectionStatusDTO[];
}

export const PROVIDER_LABELS: Record<Provider, { name: string; target: string; description: string }> = {
  SLACK: { name: 'Slack', target: '채널', description: '채널 대화를 보고 메시지를 작성합니다.' },
  NOTION: { name: 'Notion', target: '데이터베이스', description: '데이터베이스의 문서를 관리합니다.' },
  GOOGLE_DRIVE: { name: 'Google Drive', target: '폴더', description: '폴더의 파일과 산출물을 관리합니다.' },
  GOOGLE_CALENDAR: {
    name: 'Google Calendar',
    target: '팀 캘린더',
    description: '일정을 관리하고, 회의 가용 시간에 내 일정을 반영합니다. 팀 캘린더를 고르면 팀 일정이 그 캘린더에 등록됩니다.',
  },
  GMAIL: { name: 'Gmail', target: '', description: '메일에서 일정·할 일 후보를 찾습니다. (나만 볼 수 있음)' },
};

export const getIntegrations = (workspaceId: string) =>
  http.get<IntegrationStatusResponse[]>(ws(workspaceId, '/integrations'));

export const getAvailableTargets = (workspaceId: string, connectionId: number) =>
  http.get<TargetItem[]>(ws(workspaceId, `/integrations/connections/${connectionId}/available-targets`));

export const selectTargets = (workspaceId: string, connectionId: number, targets: TargetItem[]) =>
  http.post<ConnectionStatusDTO>(ws(workspaceId, `/integrations/connections/${connectionId}/targets`), { targets });

export const skipTargets = (workspaceId: string, connectionId: number) =>
  http.post<ConnectionStatusDTO>(ws(workspaceId, `/integrations/connections/${connectionId}/skip-targets`));

export const disconnect = (workspaceId: string, connectionId: number) =>
  http.delete<void>(ws(workspaceId, `/integrations/connections/${connectionId}`));

/** 연결·재인증은 페이지 이동으로 진행한다. */
export const connectUrl = (workspaceId: string, provider: Provider) =>
  `/api/workspaces/${workspaceId}/integrations/${provider}/connect`;

export const reauthUrl = (workspaceId: string, connectionId: number) =>
  `/api/workspaces/${workspaceId}/integrations/connections/${connectionId}/reauth`;
