import { http, ws } from '../../api/client';

export type Provider = 'SLACK' | 'NOTION' | 'GOOGLE_DRIVE' | 'GOOGLE_CALENDAR' | 'GMAIL';
export type ConnectionStatus = 'TARGET_NOT_SELECTED' | 'CONNECTED' | 'REAUTH_REQUIRED' | 'DISCONNECTED';

export interface TargetItem {
  id: string;
  name: string | null;
  /** Drive 폴더의 상위 폴더. 폴더를 고르면 그 아래 폴더도 함께 고르는 데 쓴다. */
  parentId?: string | null;
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
  /** 팀장이 고른 팀 캘린더 (Calendar만). 아직 안 골랐으면 null */
  teamTarget?: { id: string; name: string; chosenByUserId: number; chosenByName: string | null } | null;
  /** 연결이 끊겨 지금 아무도 쓰지 못하는 팀 채널·페이지·폴더. 누군가 다시 연결하면 되살아난다. */
  parkedTargets?: string[];
}

export const PROVIDER_LABELS: Record<Provider, { name: string; target: string; description: string }> = {
  SLACK: { name: 'Slack', target: '채널', description: '채널 대화를 보고 메시지를 작성합니다.' },
  NOTION: {
    name: 'Notion',
    target: '페이지·데이터베이스',
    description: '고른 페이지(하위 페이지 포함)와 데이터베이스의 문서를 가져옵니다.',
  },
  GOOGLE_DRIVE: { name: 'Google Drive', target: '폴더', description: '폴더의 파일과 산출물을 관리합니다.' },
  GOOGLE_CALENDAR: {
    name: 'Google Calendar',
    target: '',
    description: '일정을 관리하고, 회의 가용 시간에 내 일정을 반영합니다. 팀 일정은 팀장 캘린더에 등록되고 참여자 캘린더에도 들어갑니다.',
  },
  GMAIL: { name: 'Gmail', target: '', description: '메일에서 일정·할 일 후보를 찾습니다. (나만 볼 수 있음)' },
};

export const getIntegrations = (workspaceId: string) =>
  http.get<IntegrationStatusResponse[]>(ws(workspaceId, '/integrations'));

export const getAvailableTargets = (workspaceId: string, connectionId: number) =>
  http.get<TargetItem[]>(ws(workspaceId, `/integrations/connections/${connectionId}/available-targets`));

export const selectTargets = (workspaceId: string, connectionId: number, targets: TargetItem[]) =>
  http.post<ConnectionStatusDTO>(ws(workspaceId, `/integrations/connections/${connectionId}/targets`), { targets });

export interface DisconnectResult {
  manualRevocationRequired: boolean;
  relatedConnectionsRequireReauth: boolean;
}

export const disconnect = (workspaceId: string, connectionId: number) =>
  http.delete<DisconnectResult>(ws(workspaceId, `/integrations/connections/${connectionId}`));

/** 연결·재인증은 페이지 이동으로 진행한다. */
export const connectUrl = (workspaceId: string, provider: Provider) =>
  `/api/workspaces/${workspaceId}/integrations/${provider}/connect`;

export const reauthUrl = (workspaceId: string, connectionId: number) =>
  `/api/workspaces/${workspaceId}/integrations/connections/${connectionId}/reauth`;

export interface SyncStatus {
  provider: Provider;
  targetId: string;
  lastSyncedAt: string | null;
  lastAttemptAt: string | null;
  failed: boolean;
  lastError: string | null;
}

/** 수동 동기화 버튼: 1분 간격 제한 없이 바로 동기화 */
export const syncNow = (workspaceId: string, provider: Provider) =>
  http.post<SyncStatus[]>(ws(workspaceId, `/sync/${provider}?force=true`));
