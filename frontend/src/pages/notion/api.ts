import { http, ws } from '../../api/client';

export interface NotionDatabaseResponse {
  databaseId: string;
  name: string;
}

export interface NotionPageResponse {
  contentRefId: number;
  pageId: string;
  databaseId: string;
  title: string | null;
  lastModifiedAt: string | null;
  deepLink: string | null;
  fromAnalysis: boolean;
  isOriginDeleted: boolean;
  syncFailed: boolean;
}

export interface NotionPageDetailResponse {
  contentRefId: number;
  pageId: string;
  title: string | null;
  body: string | null;
  deepLink: string | null;
  lastModifiedAt: string | null;
  fromAnalysis: boolean;
  isOriginDeleted: boolean;
  refreshFailed: boolean;
  /** false면 이미지·표·하위 블록이 있어 본문을 ToGather에서 수정할 수 없다 (제목만 가능) */
  bodyEditable: boolean;
}

export interface ContentRefDTO {
  contentRefId: number;
  title: string | null;
  content: string | null;
  deepLink: string | null;
}

export interface NotionPageForm {
  title: string;
  body: string;
  databaseId?: string;
}

export const getDatabases = (workspaceId: string) =>
  http.get<NotionDatabaseResponse[]>(ws(workspaceId, '/notion/databases'));

export const getPages = (workspaceId: string) => http.get<NotionPageResponse[]>(ws(workspaceId, '/notion/pages'));

export const getPage = (workspaceId: string, id: number) =>
  http.get<NotionPageDetailResponse>(ws(workspaceId, `/notion/pages/${id}`));

export const createPage = (workspaceId: string, form: NotionPageForm) =>
  http.post<ContentRefDTO>(ws(workspaceId, '/notion/pages'), form);

export const updatePage = (workspaceId: string, id: number, form: Partial<NotionPageForm>) =>
  http.patch<ContentRefDTO>(ws(workspaceId, `/notion/pages/${id}`), form);

export const deletePage = (workspaceId: string, id: number) =>
  http.delete<void>(ws(workspaceId, `/notion/pages/${id}`));

export const syncNotion = (workspaceId: string) => http.post<unknown>(ws(workspaceId, '/sync/NOTION'));
