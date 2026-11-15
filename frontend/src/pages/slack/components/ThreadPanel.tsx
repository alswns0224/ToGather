import { useEffect } from 'react';
import { createReply, deleteMessage, getThread, updateMessage, type SlackMessageResponse } from '../api';
import { useAsync } from '../../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../../components/Status';
import Composer from './Composer';
import MessageItem from './MessageItem';

/** 스레드: root와 답글, 답글 작성, 논의 정리 진입 (UI-04) */
export default function ThreadPanel({
  refreshKey = 0,
  workspaceId,
  channelId,
  ts,
  onClose,
  onChanged,
  onAnalyze,
}: {
  /** 바뀌면 스레드를 다시 불러온다 (실시간 알림) */
  refreshKey?: number;
  workspaceId: string;
  channelId: string;
  ts: string;
  onClose: () => void;
  /** 답글 수 등 채널 목록도 다시 불러와야 할 때 */
  onChanged: () => void;
  onAnalyze?: (root: SlackMessageResponse) => void;
}) {
  const thread = useAsync(() => getThread(workspaceId, channelId, ts), [workspaceId, channelId, ts]);
  const { reload } = thread;
  useEffect(() => {
    if (refreshKey > 0) reload();
  }, [refreshKey, reload]);

  const changed = () => {
    thread.reload();
    onChanged();
  };

  return (
    <aside className="slack-thread card stack">
      <div className="row">
        <h2 className="!text-sm">스레드</h2>
        <span className="spacer" />
        {thread.data && onAnalyze && !thread.data.root.isOriginDeleted && (
          <button className="btn btn-small btn-primary" onClick={() => onAnalyze(thread.data!.root)}>
            ✨ AI 논의 정리
          </button>
        )}
        <button className="btn btn-small" onClick={onClose}>
          닫기
        </button>
      </div>

      {thread.loading && !thread.data && <Loading />}
      {thread.error && <ErrorBox error={thread.error} onRetry={thread.reload} />}
      {thread.data && (
        <>
          {thread.data.refreshFailed && (
            <div className="alert alert-error small">
              Slack에서 최신 답글을 가져오지 못해 저장된 내용을 보여줍니다.
              <button className="btn btn-small" onClick={thread.reload}>
                다시 시도
              </button>
            </div>
          )}
          <ul className="list">
            <MessageItem
              message={thread.data.root}
              onUpdate={async (text) => {
                await updateMessage(workspaceId, channelId, thread.data!.root.ts, text);
                changed();
              }}
              onDelete={async () => {
                await deleteMessage(workspaceId, channelId, thread.data!.root.ts);
                changed();
              }}
            />
          </ul>
          <div className="small muted">답글 {thread.data.replies.length}개</div>
          {thread.data.replies.length === 0 ? (
            <Empty>아직 답글이 없습니다.</Empty>
          ) : (
            <ul className="list slack-replies">
              {thread.data.replies.map((r) => (
                <MessageItem
                  key={r.ts}
                  message={r}
                  onUpdate={async (text) => {
                    await updateMessage(workspaceId, channelId, r.ts, text);
                    changed();
                  }}
                  onDelete={async () => {
                    await deleteMessage(workspaceId, channelId, r.ts);
                    changed();
                  }}
                />
              ))}
            </ul>
          )}
          {!thread.data.root.isOriginDeleted && (
            <Composer
              placeholder="답글 작성"
              onSubmit={async (text) => {
                await createReply(workspaceId, channelId, ts, text);
                changed();
              }}
            />
          )}
        </>
      )}
    </aside>
  );
}
