// 내 Slack 1:1 DM 대화 보기·답장 (UI-04). 저장하지 않고 열 때마다 Slack에서 바로 읽는다.
import { useEffect, useState } from 'react';
import { deleteDm, getDmMessages, sendDm, updateDm, type SlackDmMessage } from '../api';
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
          <DmMessageItem key={m.ts} workspaceId={workspaceId} channelId={channelId} message={m} onChanged={reload} />
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

function DmMessageItem({ workspaceId, channelId, message, onChanged }: {
  workspaceId: string;
  channelId: string;
  message: SlackDmMessage;
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const remove = async () => {
    if (deleting) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      await deleteDm(workspaceId, channelId, message.ts);
      setConfirmingDelete(false);
      onChanged();
    } catch (error) {
      setDeleteError((error as Error).message);
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="slack-message">
      <Avatar name={message.userName} />
      <div className="slack-message-body">
        <div className="slack-message-meta">
          <strong>{message.mine ? `${message.userName} (나)` : message.userName}</strong>
          {message.createdAt && <span title={formatDateTime(message.createdAt)}>{formatTime(message.createdAt)}</span>}
          {message.edited && <span>(수정됨)</span>}
          {message.mine && !editing && !confirmingDelete && (
            <span className="slack-message-actions ml-auto">
              <button type="button" className="link-button" disabled={deleting}
                aria-label="내 DM 수정" onClick={() => { setDeleteError(null); setEditing(true); }}>수정</button>
              <button type="button" className="link-button" disabled={deleting}
                aria-label="내 DM 삭제" onClick={() => { setDeleteError(null); setConfirmingDelete(true); }}>삭제</button>
            </span>
          )}
        </div>
        {editing ? (
          <Composer placeholder="DM 수정" initialText={message.rawText ?? message.text} submitLabel="저장"
            onSubmit={async (text) => {
              await updateDm(workspaceId, channelId, message.ts, text);
              setEditing(false);
              onChanged();
            }} onCancel={() => setEditing(false)} />
        ) : <p className="pre slack-text">{message.text}</p>}
        {confirmingDelete && <div className="mt-3 rounded-xl border border-[var(--border)] bg-[var(--surface-2)] p-3" role="group" aria-label="DM 삭제 확인">
          <p className="m-0 text-xs">이 DM을 삭제할까요? Slack에서도 삭제됩니다.</p>
          <div className="mt-2 flex justify-end gap-2">
            <button type="button" className="btn btn-small" disabled={deleting}
              onClick={() => { setConfirmingDelete(false); setDeleteError(null); }}>취소</button>
            <button type="button" className="btn btn-small btn-danger" disabled={deleting}
              onClick={() => void remove()}>{deleting ? '삭제 중...' : '삭제하기'}</button>
          </div>
        </div>}
        {deleteError && <div className="alert alert-error" role="alert">삭제하지 못했습니다. {deleteError}</div>}
      </div>
    </div>
  );
}
