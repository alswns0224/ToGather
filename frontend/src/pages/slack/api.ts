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

/** 내 Slack 1:1 DM. 내 Slack 연결로만 읽고 보낸다. (저장하지 않고 바로 읽음) */
export interface SlackDmConversation {
  channelId: string;
  userId: string | null;
  name: string;
  /** Slack에서 아직 안 읽은 메시지 수 (새 DM 웹훅이 오면 늘어난다) */
  unreadCount: number;
  /** Slack에서 마지막으로 읽은 메시지 ts */
  lastRead: string | null;
}

/**
 * ToGather에서 DM을 열어 봤을 때의 안 읽은 수. Slack 읽음 표시를 바꿀 권한이 없어 이 브라우저에 기억해 두고,
 * 그 뒤에 늘어난 수만 보여 준다. Slack에서 읽어서 읽은 위치(lastRead)가 바뀌면 기억한 값은 버린다.
 */
interface DmSeen {
  lastRead: string | null;
  count: number;
}

const seenKey = (workspaceId: string, channelId: string) => `togather.dm.seen.${workspaceId}.${channelId}`;

const getDmSeen = (workspaceId: string, channelId: string): DmSeen | null => {
  try {
    return JSON.parse(window.localStorage.getItem(seenKey(workspaceId, channelId)) ?? 'null') as DmSeen | null;
  } catch {
    return null;
  }
};

export const markDmSeen = (workspaceId: string, dm: SlackDmConversation) =>
  window.localStorage.setItem(
    seenKey(workspaceId, dm.channelId),
    JSON.stringify({ lastRead: dm.lastRead, count: dm.unreadCount } satisfies DmSeen),
  );

/** 아직 안 본 메시지 수: Slack에서 안 읽었고, ToGather에서 열어 본 뒤에 새로 온 것 */
export const dmUnreadCount = (workspaceId: string, dm: SlackDmConversation) => {
  const seen = getDmSeen(workspaceId, dm.channelId);
  if (!seen || seen.lastRead !== dm.lastRead) return dm.unreadCount;
  return Math.max(0, dm.unreadCount - seen.count);
};

export interface SlackDmMessage {
  ts: string;
  userId: string | null;
  userName: string;
  text: string;
  createdAt: string | null;
  mine: boolean;
  rawText?: string;
  edited?: boolean;
}

const dms = (workspaceId: string, path = '') => ws(workspaceId, `/slack/dms${path}`);

export const getDmConversations = (workspaceId: string) => http.get<SlackDmConversation[]>(dms(workspaceId));

export const getDmMessages = (workspaceId: string, channelId: string) =>
  http.get<SlackDmMessage[]>(dms(workspaceId, `/${enc(channelId)}/messages`));

export const sendDm = (workspaceId: string, channelId: string, text: string) =>
  http.post<SlackDmMessage>(dms(workspaceId, `/${enc(channelId)}/messages`), { text });

export const updateDm = (workspaceId: string, channelId: string, ts: string, text: string) =>
  http.patch<SlackDmMessage>(dms(workspaceId, `/${enc(channelId)}/messages/${enc(ts)}`), { text });

export const deleteDm = (workspaceId: string, channelId: string, ts: string) =>
  http.delete<void>(dms(workspaceId, `/${enc(channelId)}/messages/${enc(ts)}`));

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
/** force: 동기화 버튼을 직접 누른 경우 1분 간격 제한 없이 바로 동기화 */
export const syncSlack = (workspaceId: string, force = false) =>
  http.post<SyncStatusResponse[]>(ws(workspaceId, `/sync/SLACK${force ? '?force=true' : ''}`));
