// UI-04 커뮤니케이션 (Slack) (담당: B) — 팀원 UI 시안(togather_ui.html)의 한 카드 3단 배치
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  createMessage,
  deleteMessage,
  getChannelMessages,
  getChannels,
  syncSlack,
  updateMessage,
  type SlackMessageResponse,
} from './api';
import { useAsync } from '../../hooks/useAsync';
import { useWorkspaceEvents } from '../../hooks/useWorkspaceEvents';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import NavIcon from '../../layouts/NavIcon';
import { BTN_SUB } from '../../components/ui';
import { formatDateTime, fromLocalIso, sameDay } from '../../utils/date';
import Composer from './components/Composer';
import MessageItem from './components/MessageItem';
import ThreadPanel from './components/ThreadPanel';
import './slack.css';

const PAGE_SIZE = 30;
/** 실시간 알림이 빠졌을 때를 대비한 보조 동기화 간격 (화면을 보고 있을 때만) */
const FALLBACK_SYNC_MS = 60_000;
const DAYS = ['일', '월', '화', '수', '목', '금', '토'];

export default function SlackPage() {
  const { workspaceId = '' } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const channels = useAsync(() => getChannels(workspaceId), [workspaceId]);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [syncTick, setSyncTick] = useState(0);
  const [channelQuery, setChannelQuery] = useState('');

  const channelId = params.get('channel') ?? channels.data?.[0]?.channelId ?? null;
  const threadTs = params.get('thread');
  const channel = channels.data?.find((c) => c.channelId === channelId);
  const visibleChannels = (channels.data ?? []).filter(
    (c) => !channelQuery.trim() || c.name.toLowerCase().includes(channelQuery.trim().toLowerCase()),
  );
  const anyFailed = channels.data?.some((c) => c.syncFailed) ?? false;

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

  // Slack에서 메시지를 쓰거나 고치거나 지우면 서버가 바로 알려준다. 보고 있는 채널·스레드만 그 자리에서 다시 불러온다.
  const [liveTick, setLiveTick] = useState(0);
  useWorkspaceEvents(workspaceId, (event) => {
    if (event.provider !== 'SLACK') return;
    channels.reload();
    if (event.targetId === channelId) setLiveTick((t) => t + 1);
  });

  // 실시간 알림을 받지 못하는 경우(Slack 이벤트 설정 전 등)에도 화면을 보고 있으면 주기적으로 가져온다.
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      syncSlack(workspaceId)
        .then(() => setLiveTick((t) => t + 1))
        .catch(() => undefined);
    }, FALLBACK_SYNC_MS);
    return () => window.clearInterval(timer);
  }, [workspaceId]);

  // 화면에 들어오면 저장된 메시지를 먼저 보여주고, 뒤에서 최신 내용을 동기화한다.
  const synced = useRef(false);
  useEffect(() => {
    if (synced.current) return;
    synced.current = true;
    void sync();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  const connected = (channels.data?.length ?? 0) > 0;

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="slack" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="Slack"
        description="팀의 대화를 모으고, 논의를 작업으로 이어보세요."
        actions={
          <>
            {channels.data &&
              (connected ? (
                <span className="badge badge-success">● 연결됨</span>
              ) : (
                <span className="badge">연결 안 됨</span>
              ))}
            {channel?.lastSyncedAt && (
              <span className="text-xs text-[var(--muted)]">마지막 동기화 {formatDateTime(channel.lastSyncedAt)}</span>
            )}
            <Link to={`/workspaces/${workspaceId}/slack/analyses`} className={`${BTN_SUB} !h-[34px]`}>
              ✦ 논의 정리 검토 대기
            </Link>
            <button type="button" className={`${BTN_SUB} !h-[34px]`} onClick={sync} disabled={syncing}>
              {syncing ? '동기화 중...' : '↻ 동기화'}
            </button>
          </>
        }
      />

      {syncError && <div className="alert alert-error">동기화하지 못했습니다. 저장된 메시지를 보여줍니다. ({syncError})</div>}

      {channels.loading && !channels.data && <Loading />}
      {channels.error && <ErrorBox error={channels.error} onRetry={channels.reload} />}
      {channels.data && channels.data.length === 0 && (
        <Empty>
          연동된 Slack 채널이 없습니다.{' '}
          <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리</Link>에서 Slack을 연결하고 채널을 선택해 주세요.
        </Empty>
      )}

      {channels.data && channels.data.length > 0 && channelId && (
        <div className="slack-board">
          <nav className="slack-channels">
            <div className="slack-col-title">채널</div>
            <input
              className="input slack-channel-search"
              placeholder="🔍 채널 찾기"
              value={channelQuery}
              onChange={(e) => setChannelQuery(e.target.value)}
            />
            <div className="slack-channel-list">
              {visibleChannels.map((c) => (
                <button
                  key={c.channelId}
                  type="button"
                  className={`slack-channel${c.channelId === channelId ? ' active' : ''}`}
                  onClick={() => select({ channel: c.channelId, thread: null })}
                >
                  <span># {c.name}</span>
                  {c.syncFailed && (
                    <span className="badge badge-warning" title={c.lastError ?? ''}>
                      {c.lastError === 'REAUTH_REQUIRED' ? '재인증' : '실패'}
                    </span>
                  )}
                </button>
              ))}
              {visibleChannels.length === 0 && <div className="small muted px-1">맞는 채널이 없습니다.</div>}
            </div>
            <div className="slack-channel-count">연동 채널 {channels.data.length}개</div>
            <div className={`slack-channel-state${anyFailed ? ' failed' : ''}`}>
              {anyFailed ? '● 일부 동기화 실패' : '● 최신 상태'}
            </div>
          </nav>

          <ChannelMessages
            key={`${channelId}-${syncTick}`}
            refreshKey={liveTick}
            workspaceId={workspaceId}
            channelId={channelId}
            channelName={channel?.name ?? channelId}
            lastSyncedAt={channel?.lastSyncedAt ?? null}
            selectedTs={threadTs}
            onOpenThread={(ts) => select({ thread: ts })}
          />

          {threadTs ? (
            <ThreadPanel
              key={threadTs}
              refreshKey={liveTick}
              workspaceId={workspaceId}
              channelId={channelId}
              ts={threadTs}
              onClose={() => select({ thread: null })}
              onChanged={() => setSyncTick((t) => t + 1)}
              onAnalyze={(root) =>
                root.contentRefId &&
                navigate(`/workspaces/${workspaceId}/slack/analysis/new?thread=${root.contentRefId}`)
              }
            />
          ) : (
            <aside className="slack-thread">
              <div className="slack-col-head">
                <strong className="text-[15px]">스레드</strong>
              </div>
              <div className="slack-thread-body">
                <div className="text-[13px] text-[var(--muted)]">메시지를 선택하면 스레드가 열립니다.</div>
              </div>
            </aside>
          )}
        </div>
      )}
    </section>
  );
}

/** "11월 12일 목요일" */
function dayLabel(d: Date) {
  return `${d.getMonth() + 1}월 ${d.getDate()}일 ${DAYS[d.getDay()]}요일`;
}

function ChannelMessages({
  refreshKey,
  workspaceId,
  channelId,
  channelName,
  lastSyncedAt,
  selectedTs,
  onOpenThread,
}: {
  /** 바뀌면 화면을 그대로 둔 채 메시지를 다시 불러온다 (실시간 알림) */
  refreshKey: number;
  workspaceId: string;
  channelId: string;
  channelName: string;
  lastSyncedAt: string | null;
  selectedTs: string | null;
  onOpenThread: (ts: string) => void;
}) {
  const [pages, setPages] = useState(1);
  const messages = useAsync(
    () => getChannelMessages(workspaceId, channelId, 0, PAGE_SIZE * pages),
    [workspaceId, channelId, pages],
  );
  const { reload } = messages;
  useEffect(() => {
    if (refreshKey > 0) reload();
  }, [refreshKey, reload]);
  // 서버는 최신순으로 주므로 화면에서는 시간순으로 뒤집는다.
  const items: SlackMessageResponse[] = [...(messages.data?.items ?? [])].reverse();
  const hasMore = messages.data ? messages.data.total > messages.data.items.length : false;
  const slackLink = items.find((m) => m.deepLink)?.deepLink?.replace(/\/p\d+.*$/, '');

  return (
    <div className="slack-messages">
      <div className="slack-col-head">
        <div className="min-w-0">
          <div className="text-base font-extrabold"># {channelName}</div>
          <div className="text-xs text-[var(--muted)]">
            {lastSyncedAt ? `마지막 동기화 ${formatDateTime(lastSyncedAt)}` : '연동한 Slack 채널의 대화'}
          </div>
        </div>
        {slackLink && (
          <a className={`${BTN_SUB} !h-[30px] !text-xs`} href={slackLink} target="_blank" rel="noreferrer">
            ↗ Slack에서 열기
          </a>
        )}
      </div>

      <div className="slack-message-scroll">
        {messages.loading && !messages.data && <Loading />}
        {messages.error && <ErrorBox error={messages.error} onRetry={messages.reload} />}
        {messages.data && (
          <>
            {hasMore && (
              <button
                type="button"
                className={`${BTN_SUB} !h-[30px] self-center !text-xs`}
                onClick={() => setPages((p) => p + 1)}
                disabled={messages.loading}
              >
                이전 메시지 더 보기
              </button>
            )}
            {items.length === 0 ? (
              <Empty>아직 메시지가 없습니다.</Empty>
            ) : (
              items.map((m, i) => {
                const day = fromLocalIso(m.createdAt);
                const newDay = i === 0 || !sameDay(fromLocalIso(items[i - 1].createdAt), day);
                return (
                  <div key={m.ts} className="contents">
                    {newDay && (
                      <div className="slack-day">
                        <span>{dayLabel(day)}</span>
                      </div>
                    )}
                    <MessageItem
                      message={m}
                      selected={m.ts === selectedTs}
                      onOpenThread={() => onOpenThread(m.ts)}
                      onUpdate={async (text) => {
                        await updateMessage(workspaceId, channelId, m.ts, text);
                        messages.reload();
                      }}
                      onDelete={async () => {
                        await deleteMessage(workspaceId, channelId, m.ts);
                        messages.reload();
                      }}
                    />
                  </div>
                );
              })
            )}
          </>
        )}
      </div>

      <div className="slack-col-foot">
        <Composer
          placeholder={`#${channelName}에 메시지 보내기`}
          onSubmit={async (text) => {
            await createMessage(workspaceId, channelId, text);
            messages.reload();
          }}
        />
      </div>
    </div>
  );
}
