// 내 Slack 1:1 DM 대화 보기·답장 (UI-04). 저장하지 않고 열 때마다 Slack에서 바로 읽는다.
import { useEffect } from 'react';
import { getDmMessages, sendDm } from '../api';
import { useAsync } from '../../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../../components/Status';
import { formatDateTime, formatTime } from '../../../utils/date';
import Composer from './Composer';
import { Avatar } from './MessageItem';

/** 새 DM은 웹훅 알림(refreshKey)으로 바로 반영된다. 알림을 놓쳤을 때를 위해서만 가끔 다시 가져온다. */
const REFRESH_MS = 5 * 60_000;

export default function DmMessages({
  workspaceId,
  channelId,
  name,
  refreshKey,
}: {
  workspaceId: string;
  channelId: string;
  name: string;
  /** 바뀌면 다시 불러온다 (이 대화에 새 DM 이벤트가 왔을 때) */
  refreshKey: number;
}) {
  const messages = useAsync(() => getDmMessages(workspaceId, channelId), [workspaceId, channelId]);
  const { reload } = messages;
  useEffect(() => {
    if (refreshKey > 0) reload();
  }, [refreshKey, reload]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') reload();
    }, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [reload]);

  return (
    <div className="slack-messages">
      <div className="slack-col-head">
        <div className="min-w-0">
          <div className="text-base font-extrabold">@ {name}</div>
          <div className="text-xs text-[var(--muted)]">나만 보는 1:1 DM · 최근 30일</div>
        </div>
      </div>

      <div className="slack-message-scroll">
        {messages.loading && !messages.data && <Loading />}
        {messages.error && <ErrorBox error={messages.error} onRetry={reload} />}
        {messages.data && messages.data.length === 0 && <Empty>최근 30일 동안 주고받은 DM이 없습니다.</Empty>}
        {messages.data?.map((m) => (
          <div key={m.ts} className="slack-message">
            <Avatar name={m.userName} />
            <div className="slack-message-body">
              <div className="slack-message-meta">
                <strong>{m.mine ? `${m.userName} (나)` : m.userName}</strong>
                {m.createdAt && <span title={formatDateTime(m.createdAt)}>{formatTime(m.createdAt)}</span>}
              </div>
              <p className="pre slack-text">{m.text}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="slack-col-foot">
        <Composer
          placeholder={`${name}에게 메시지 보내기`}
          onSubmit={async (text) => {
            await sendDm(workspaceId, channelId, text);
            reload();
          }}
        />
      </div>
    </div>
  );
}
