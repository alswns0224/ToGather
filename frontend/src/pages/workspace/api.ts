import { http, ws } from '../../api/client';

export type WorkspaceRole = 'OWNER' | 'MEMBER';

export interface WorkspaceResponse {
  workspaceId: number;
  name: string;
  description: string | null;
  memberCount: number;
  myRole: WorkspaceRole;
  createdAt: string;
}

export interface WorkspaceRequest {
  name: string;
  description?: string;
}

export interface MemberResponse {
  userId: number;
  name: string;
  email: string;
  profileImage: string | null;
  role: WorkspaceRole;
  joinedAt: string;
}

export interface InviteLinkResponse {
  token: string;
  url: string;
  expiresAt: string;
}

export interface InviteInfoResponse {
  workspaceId: number;
  name: string;
  description: string | null;
  memberCount: number;
  alreadyMember: boolean;
}

export const getWorkspaces = () => http.get<WorkspaceResponse[]>('/workspaces');
export const getWorkspace = (id: string | number) => http.get<WorkspaceResponse>(ws(id));
export const createWorkspace = (body: WorkspaceRequest) => http.post<WorkspaceResponse>('/workspaces', body);
export const updateWorkspace = (id: string | number, body: WorkspaceRequest) =>
  http.patch<WorkspaceResponse>(ws(id), body);
export const deleteWorkspace = (id: string | number) => http.delete<void>(ws(id));

export const getMembers = (id: string | number) => http.get<MemberResponse[]>(ws(id, '/members'));
export const removeMember = (id: string | number, userId: number) =>
  http.delete<MemberResponse[]>(ws(id, `/members/${userId}`));

export const createInviteLink = (id: string | number) => http.post<InviteLinkResponse>(ws(id, '/invite-links'));
export const getActiveInviteLink = (id: string | number) =>
  http.get<InviteLinkResponse | null>(ws(id, '/invite-links/active'));
export const getInviteInfo = (token: string) => http.get<InviteInfoResponse>(`/invites/${token}`);
export const joinByInviteLink = (token: string) => http.post<{ workspaceId: number }>(`/invites/${token}/join`);
