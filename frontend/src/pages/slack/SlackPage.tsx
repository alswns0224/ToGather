// UI-04 대화 (Slack) (담당: B)
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  createMessage,
  deleteMessage,
  getChannelMessages,
  getChannels,
  syncSlack,
  updateMessage,
  type SlackChannelResponse,
  type SlackMessageResponse,
} from './api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import Icon from '../../components/Icon';
import { formatDate, formatDateTime, sameDay, fromLocalIso } from '../../utils/date';
import Composer from './components/Composer';
import MessageItem from './components/MessageItem';
import ThreadPanel from './components/ThreadPanel';
import './slack.css';

const PAGE_SIZE = 30;

export default function SlackPage() {
  const { workspaceId = '' } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const channels = useAsync(() => getChannels(workspaceId), [workspaceId]);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [syncTick, setSyncTick] = useState(0);

  const channelId = params.get('channel') ?? channels.data?.[0]?.channelId ?? null;
  const threadTs = params.get('thread');
  const channel = channels.data?.find((c) => c.channelId === channelId);

  const select = (next: Record<string, string | null>) => {
    const p = new URLSearchParams(params);
    Object.entries(next).forEach(([k, v]) => (v === null ? p.delete(k) : p.set(k, v)));
    setParams(p, { replace: true });
  };

  const sync = async () => {
    setSyncing(true);
    setSyncError(null);
    try {
      await syncSlack(workspaceId);
    } catch (e) {
      setSyncError((e as Error).message);
    } finally {
      setSyncing(false);
      channels.reload();
      setSyncTick((t) => t + 1);
    }
  };

  // 저장된 메시지를 먼저 보여주고 뒤에서 최신 내용을 동기화한다. (서버는 1분 안에 다시 동기화하지 않는다)
  const synced = useRef(false);
  useEffect(() => {
    if (synced.current) return;
    synced.current = true;
    void sync();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  if (channels.loading && !channels.data) {
    return (
      <div className="app-content">
        <Loading />
      </div>
    );
  }
  if (channels.error) {
    return (
      <div className="app-content">
        <ErrorBox error={channels.error} onRetry={channels.reload} />
      </div>
    );
  }
  if (!channels.data?.length || !channelId) {
    return (
      <div className="app-content">
        <Empty icon="chat">
          연동된 Slack 채널이 없어요. <Link to={`/workspaces/${workspaceId}/integrations`}>서비스 연동</Link>에서
          Slack을 연결하고 채널을 선택해 주세요.
        </Empty>
      </div>
    );
  }

  return (
    <div className="chat-layout">
      <aside className="channel-panel">
        <div className="channel-heading">
          <div>
            <strong>Slack</strong>
            <span>{channel?.lastSyncedAt ? `동기화 ${formatDateTime(channel.lastSyncedAt)}` : '동기화 전'}</span>
          </div>
          <button type="button" className="plain-icon" onClick={sync} disabled={syncing} aria-label="새로고침" title="새로고침">
            <Icon name="retry" size={15} />
          </button>
        </div>
        <div className="channel-section-title">
          <span>채널</span>
        </div>
        <div className="channel-list">
          {channels.data.map((c) => (
            <ChannelButton
              key={c.channelId}
              channel={c}
              active={c.channelId === channelId}
              onClick={() => select({ channel: c.channelId, thread: null })}
            />
          ))}
        </div>
        {syncError && <div className="notice error" style={{ marginTop: 12 }}>동기화 실패: {syncError}</div>}
      </aside>

      <ChannelMessages
        key={`${channelId}-${syncTick}`}
        workspaceId={workspaceId}
        channel={channel!}
        selectedTs={threadTs}
        onOpenThread={(ts) => select({ thread: ts })}
      />

      <ThreadPanel
        workspaceId={workspaceId}
        channelId={channelId}
        channelName={channel?.name ?? channelId}
        ts={threadTs}
        onClose={() => select({ thread: null })}
        onChanged={() => setSyncTick((t) => t + 1)}
        onAnalyze={(root) => navigate(`/workspaces/${workspaceId}/slack/analysis/new?thread=${root.contentRefId}`)}
      />
    </div>
  );
}

function ChannelButton({
  channel,
  active,
  onClick,
}: {
  channel: SlackChannelResponse;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button type="button" className={active ? 'active' : ''} onClick={onClick} title={channel.lastError ?? undefined}>
      <span>#</span>
      {channel.name}
      {channel.syncFailed && (
        <span className="status-tag warning">{channel.lastError === 'REAUTH_REQUIRED' ? '재인증' : '실패'}</span>
      )}
    </button>
  );
}

function ChannelMessages({
  workspaceId,
  channel,
  selectedTs,
  onOpenThread,
}: {
  workspaceId: string;
  channel: SlackChannelResponse;
  selectedTs: string | null;
  onOpenThread: (ts: string) => void;
}) {
  const [pages, setPages] = useState(1);
  const messages = useAsync(
    () => getChannelMessages(workspaceId, channel.channelId, 0, PAGE_SIZE * pages),
    [workspaceId, channel.channelId, pages],
  );
  // 서버는 최신순으로 주므로 화면에서는 시간순으로 뒤집는다.
  const items: SlackMessageResponse[] = [...(messages.data?.items ?? [])].reverse();
  const hasMore = messages.data ? messages.data.total > messages.data.items.length : false;
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (pages === 1) bottomRef.current?.scrollIntoView({ block: 'end' });
  }, [messages.data, pages]);

  return (
    <section className="message-panel">
      <div className="message-header">
        <div>
          <strong># {channel.name}</strong>
          <span>
            {channel.syncFailed
              ? '마지막 동기화에 실패했어요. 저장된 메시지를 보여줘요.'
              : '메시지를 누르면 오른쪽에서 스레드를 볼 수 있어요.'}
          </span>
        </div>
      </div>

      <div className="message-list">
        {messages.loading && !messages.data && <Loading />}
        {messages.error && <ErrorBox error={messages.error} onRetry={messages.reload} />}
        {messages.data && items.length === 0 && <Empty icon="chat">아직 메시지가 없어요.</Empty>}
        {hasMore && (
          <div className="load-older">
            <button type="button" className="ui-button secondary" onClick={() => setPages((p) => p + 1)} disabled={messages.loading}>
              이전 메시지 더 보기
            </button>
          </div>
        )}
        {items.map((m, i) => (
          <div key={m.ts}>
            {(i === 0 || !sameDay(fromLocalIso(items[i - 1].createdAt), fromLocalIso(m.createdAt))) && (
              <div className="day-divider">
                <span>{formatDate(m.createdAt)}</span>
              </div>
            )}
            <MessageItem
              message={m}
              layout="channel"
              selected={m.ts === selectedTs}
              onOpenThread={() => onOpenThread(m.ts)}
              onUpdate={async (text) => {
                await updateMessage(workspaceId, channel.channelId, m.ts, text);
                messages.reload();
              }}
              onDelete={async () => {
                await deleteMessage(workspaceId, channel.channelId, m.ts);
                messages.reload();
              }}
            />
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      <Composer
        variant="message"
        placeholder={`#${channel.name}에 메시지 보내기`}
        onSubmit={async (text) => {
          await createMessage(workspaceId, channel.channelId, text);
          messages.reload();
        }}
      />
    </section>
  );
}
