import { useState } from 'react';
import type { SlackMessageResponse } from '../api';
import { formatDateTime } from '../../../utils/date';
import Composer from './Composer';

/** 메시지 한 건: 작성자, 작성 시각, 본문, 첨부 파일 (UI-04) */
export default function MessageItem({
  message,
  selected,
  onOpenThread,
  onUpdate,
  onDelete,
}: {
  message: SlackMessageResponse;
  selected?: boolean;
  onOpenThread?: () => void;
  onUpdate: (text: string) => Promise<void>;
  onDelete: () => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const remove = async () => {
    if (!window.confirm('이 메시지를 삭제할까요? Slack에서도 삭제됩니다.')) return;
    setDeleteError(null);
    try {
      await onDelete();
    } catch (e) {
      setDeleteError((e as Error).message);
    }
  };

  return (
    <li className={`slack-message${selected ? ' selected' : ''}`}>
      <div className="row">
        <strong>{message.authorName ?? '알 수 없음'}</strong>
        <span className="small muted">{formatDateTime(message.createdAt)}</span>
        {message.edited && <span className="small muted">(수정됨)</span>}
        {message.syncFailed && <span className="badge badge-warning">동기화 실패</span>}
        <span className="spacer" />
        {message.deepLink && !message.isOriginDeleted && (
          <a className="small" href={message.deepLink} target="_blank" rel="noreferrer">
            Slack에서 보기
          </a>
        )}
        {message.mine && !message.isOriginDeleted && !editing && (
          <>
            <button className="link-button small" onClick={() => setEditing(true)}>
              수정
            </button>
            <button className="link-button small" onClick={remove}>
              삭제
            </button>
          </>
        )}
      </div>

      {message.isOriginDeleted && <p className="small muted">Slack에서 삭제된 메시지입니다. (보관된 사본)</p>}

      {editing ? (
        <Composer
          placeholder="메시지 수정"
          initialText={message.text}
          submitLabel="저장"
          onSubmit={async (text) => {
            await onUpdate(text);
            setEditing(false);
          }}
          onCancel={() => setEditing(false)}
        />
      ) : (
        <p className={`pre slack-text${message.isOriginDeleted ? ' muted' : ''}`}>{message.text}</p>
      )}

      {message.attachments.length > 0 && (
        <div className="tags">
          {message.attachments.map((a, i) =>
            a.url ? (
              <a key={i} className="tag" href={a.url} target="_blank" rel="noreferrer">
                📎 {a.name}
              </a>
            ) : (
              <span key={i} className="tag">
                📎 {a.name}
              </span>
            ),
          )}
        </div>
      )}

      {deleteError && <div className="alert alert-error">삭제하지 못했습니다. {deleteError}</div>}

      {onOpenThread && (
        <button className="link-button small" onClick={onOpenThread}>
          {message.replyCount > 0 ? `답글 ${message.replyCount}개` : '스레드 열기'}
        </button>
      )}
    </li>
  );
}
