import { api, http, ws } from '../../api/client';

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

// ===================== 블록 단위 편집 (이미지·표가 있는 문서) =====================

/** TEXT 글 한 줄 · TABLE 표 · IMAGE 이미지 · FIXED 고칠 수 없는 블록(코드·토글·하위 페이지 등) */
export interface NotionBlock {
  id: string;
  kind: 'TEXT' | 'TABLE' | 'IMAGE' | 'FIXED';
  text: string | null;
  label: string | null;
  table: {
    width: number;
    hasColumnHeader: boolean;
    hasRowHeader: boolean;
    rows: { id: string; cells: string[] }[];
  } | null;
  image: { url: string | null; caption: string } | null;
}

export interface NotionEditorResponse {
  title: string;
  blocks: NotionBlock[];
}

/** 저장할 때 보내는 칸 (위에서부터 순서대로) */
export interface NotionSaveItem {
  kind: 'TEXT' | 'TABLE' | 'IMAGE' | 'FIXED';
  id?: string | null;
  blockIds?: string[];
  text?: string;
  changed?: boolean;
  width?: number;
  rows?: { id: string | null; cells: string[]; changed: boolean }[];
  deletedRowIds?: string[];
  url?: string | null;
  fileUploadId?: string | null;
  caption?: string;
  deleted?: boolean;
}

export const getPageBlocks = (workspaceId: string, id: number) =>
  http.get<NotionEditorResponse>(ws(workspaceId, `/notion/pages/${id}/blocks`));

export const savePageBlocks = (workspaceId: string, id: number, body: { title?: string; items: NotionSaveItem[] }) =>
  api<ContentRefDTO>(ws(workspaceId, `/notion/pages/${id}/blocks`), { method: 'PUT', body: JSON.stringify(body) });

/** 이미지 파일을 Notion에 올린다. 돌려받은 ID를 1시간 안에 저장해야 문서에 붙는다. */
export const uploadPageImage = (workspaceId: string, id: number, file: File) => {
  const form = new FormData();
  form.append('file', file);
  return api<{ fileUploadId: string }>(ws(workspaceId, `/notion/pages/${id}/images`), { method: 'POST', body: form });
};

/** force: 동기화 버튼을 직접 누른 경우 1분 간격 제한 없이 바로 동기화 (자동 동기화는 false) */
export const syncNotion = (workspaceId: string, force = false) =>
  http.post<unknown>(ws(workspaceId, `/sync/NOTION${force ? '?force=true' : ''}`));
