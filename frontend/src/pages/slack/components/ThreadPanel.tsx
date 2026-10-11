import { createReply, deleteMessage, getThread, updateMessage, type SlackMessageResponse } from '../api';
import { useAsync } from '../../../hooks/useAsync';
import { ErrorBox, Loading } from '../../../components/Status';
import Icon from '../../../components/Icon';
import Composer from './Composer';
import MessageItem from './MessageItem';

/** 오른쪽 스레드 패널: 본문, 답글, 첨부 파일, "Slack에서 열기", 답글 입력, 논의 정리 진입 (UI-04) */
export default function ThreadPanel({
  workspaceId,
  channelId,
  channelName,
  ts,
  onClose,
  onChanged,
  onAnalyze,
}: {
  workspaceId: string;
  channelId: string;
  channelName: string;
  ts: string | null;
  onClose: () => void;
  onChanged: () => void;
  onAnalyze: (root: SlackMessageResponse) => void;
}) {
  if (!ts) {
    return (
      <aside className="thread-panel">
        <div className="thread-header">
          <div>
            <strong>스레드</strong>
            <span># {channelName}</span>
          </div>
        </div>
        <div className="thread-empty">
          <Icon name="chat" size={30} />
          <strong>메시지를 선택하세요</strong>
          <span>메시지를 누르면 스레드 답글을 보고, 논의를 정리할 수 있어요.</span>
        </div>
      </aside>
    );
  }
  return (
    <ThreadDetail
      key={ts}
      workspaceId={workspaceId}
      channelId={channelId}
      channelName={channelName}
      ts={ts}
      onClose={onClose}
      onChanged={onChanged}
      onAnalyze={onAnalyze}
    />
  );
}

function ThreadDetail({
  workspaceId,
  channelId,
  channelName,
  ts,
  onClose,
  onChanged,
  onAnalyze,
}: {
  workspaceId: string;
  channelId: string;
  channelName: string;
  ts: string;
  onClose: () => void;
  onChanged: () => void;
  onAnalyze: (root: SlackMessageResponse) => void;
}) {
  const thread = useAsync(() => getThread(workspaceId, channelId, ts), [workspaceId, channelId, ts]);
  const changed = () => {
    thread.reload();
    onChanged();
  };
  const root = thread.data?.root;

  return (
    <aside className="thread-panel">
      <div className="thread-header">
        <div>
          <strong>스레드</strong>
          <span># {channelName}</span>
        </div>
        <div className="actions">
          {root && !root.isOriginDeleted && root.contentRefId && (
            <button type="button" className="ui-button primary" onClick={() => onAnalyze(root)}>
              <Icon name="spark" size={14} />
              논의 정리
            </button>
          )}
          <button type="button" className="plain-icon" onClick={onClose} aria-label="스레드 닫기">
            <Icon name="close" size={16} />
          </button>
        </div>
      </div>

      <div className="thread-scroll">
        {thread.loading && !thread.data && <Loading rows={2} />}
        {thread.error && <ErrorBox error={thread.error} onRetry={thread.reload} />}
        {thread.data && root && (
          <>
            {thread.data.refreshFailed && (
              <div className="notice warning">
                <span>Slack에서 최신 답글을 가져오지 못해 저장된 내용을 보여줘요.</span>
                <button type="button" className="ui-button secondary" onClick={thread.reload}>
                  다시 시도
                </button>
              </div>
            )}
            <MessageItem
              message={root}
              layout="original"
              onUpdate={async (text) => {
                await updateMessage(workspaceId, channelId, root.ts, text);
                changed();
              }}
              onDelete={async () => {
                await deleteMessage(workspaceId, channelId, root.ts);
                changed();
              }}
            />
            <div className="thread-divider">
              <span>{thread.data.replies.length}개의 답글</span>
            </div>
            {thread.data.replies.map((r) => (
              <MessageItem
                key={r.ts}
                message={r}
                layout="reply"
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
            {thread.data.permalink && (
              <a className="slack-link" href={thread.data.permalink} target="_blank" rel="noreferrer">
                <Icon name="external" size={14} />
                Slack에서 열기
              </a>
            )}
          </>
        )}
      </div>

      {root && !root.isOriginDeleted && (
        <Composer
          variant="thread"
          placeholder="답글을 입력하세요"
          onSubmit={async (text) => {
            await createReply(workspaceId, channelId, ts, text);
            changed();
          }}
        />
      )}
    </aside>
  );
}
