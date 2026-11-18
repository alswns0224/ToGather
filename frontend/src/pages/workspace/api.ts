import { http, ws } from '../../api/client';

/** OWNER 팀장(한 명) · ADMIN 관리자 · MEMBER 팀원 */
export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER';

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
/** 관리자 임명(ADMIN) · 해제(MEMBER) — 팀장만 */
export const changeMemberRole = (id: string | number, userId: number, role: WorkspaceRole) =>
  http.patch<MemberResponse[]>(ws(id, `/members/${userId}/role`), { role });
/** 팀장 넘기기: 대상은 팀장, 나는 팀원이 된다 */
export const transferOwner = (id: string | number, userId: number) =>
  http.post<MemberResponse[]>(ws(id, `/members/${userId}/transfer-owner`));

export const createInviteLink = (id: string | number) => http.post<InviteLinkResponse>(ws(id, '/invite-links'));
export const getActiveInviteLink = (id: string | number) =>
  http.get<InviteLinkResponse | null>(ws(id, '/invite-links/active'));
export const getInviteInfo = (token: string) => http.get<InviteInfoResponse>(`/invites/${token}`);
export const joinByInviteLink = (token: string) => http.post<{ workspaceId: number }>(`/invites/${token}/join`);
