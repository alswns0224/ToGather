import { http, ws, type PageData } from '../../api/client';

export interface SlackChannelResponse {
  channelId: string;
  name: string;
  lastSyncedAt: string | null;
  syncFailed: boolean;
  lastError: string | null;
}

export interface Attachment {
  name: string;
  url: string | null;
}

export interface SlackMessageResponse {
  contentRefId: number | null;
  channelId: string;
  ts: string;
  authorId: string | null;
  authorName: string | null;
  text: string;
  attachments: Attachment[];
  createdAt: string;
  edited: boolean;
  replyCount: number;
  deepLink: string | null;
  isOriginDeleted: boolean;
  syncFailed: boolean;
  mine: boolean;
}

export interface ThreadDetailResponse {
  root: SlackMessageResponse;
  replies: SlackMessageResponse[];
  permalink: string | null;
  refreshFailed: boolean;
}

export interface SyncStatusResponse {
  provider: string;
  targetId: string;
  lastSyncedAt: string | null;
  lastAttemptAt: string | null;
  failed: boolean;
  lastError: string | null;
}

const channels = (workspaceId: string, path = '') => ws(workspaceId, `/slack/channels${path}`);
const enc = encodeURIComponent;

export const getChannels = (workspaceId: string) => http.get<SlackChannelResponse[]>(channels(workspaceId));

export const getChannelMessages = (workspaceId: string, channelId: string, page = 0, size = 30) =>
  http.get<PageData<SlackMessageResponse>>(
    channels(workspaceId, `/${enc(channelId)}/messages?page=${page}&size=${size}`),
  );

export const getThread = (workspaceId: string, channelId: string, ts: string) =>
  http.get<ThreadDetailResponse>(channels(workspaceId, `/${enc(channelId)}/messages/${enc(ts)}/thread`));

export const createMessage = (workspaceId: string, channelId: string, text: string) =>
  http.post<SlackMessageResponse>(channels(workspaceId, `/${enc(channelId)}/messages`), { text });

export const createReply = (workspaceId: string, channelId: string, threadTs: string, text: string) =>
  http.post<SlackMessageResponse>(channels(workspaceId, `/${enc(channelId)}/messages/${enc(threadTs)}/replies`), {
    text,
  });

export const updateMessage = (workspaceId: string, channelId: string, ts: string, text: string) =>
  http.patch<SlackMessageResponse>(channels(workspaceId, `/${enc(channelId)}/messages/${enc(ts)}`), { text });

export const deleteMessage = (workspaceId: string, channelId: string, ts: string) =>
  http.delete<void>(channels(workspaceId, `/${enc(channelId)}/messages/${enc(ts)}`));

/** 즉시 동기화 (B-03). 실패해도 저장된 메시지는 그대로 보인다. */
export const syncSlack = (workspaceId: string) => http.post<SyncStatusResponse[]>(ws(workspaceId, '/sync/SLACK'));
