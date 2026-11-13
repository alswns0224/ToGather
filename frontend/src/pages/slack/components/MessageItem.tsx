import { useState, type MouseEvent } from 'react';
import type { SlackMessageResponse } from '../api';
import { formatDateTime, formatTime } from '../../../utils/date';
import Composer from './Composer';

const AVATAR_COLORS = ['#F97316', '#7C3AED', '#0D9488', '#E11D48', '#2563EB', '#D97706'];

/** 이름마다 늘 같은 아바타 색 */
function avatarColor(name: string | null) {
  const s = name ?? '';
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

export function Avatar({ name }: { name: string | null }) {
  return (
    <span className="slack-avatar" style={{ background: avatarColor(name) }}>
      {(name ?? '?').slice(0, 1)}
    </span>
  );
}

/** 파일 이름의 확장자 (첨부 상자 표시용) */
function ext(name: string) {
  const m = /\.([a-z0-9]{1,5})$/i.exec(name);
  return m ? m[1].toUpperCase() : 'FILE';
}

/**
 * 메시지 한 줄: 아바타, 작성자, 시각, 본문, 첨부, 답글 수 (UI-04)
 * 채널 목록에서는 줄을 누르면 스레드가 열리고, 스레드 안에서는 그냥 보여준다.
 */
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

  const remove = async (e: MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm('이 메시지를 삭제할까요? Slack에서도 삭제됩니다.')) return;
    setDeleteError(null);
    try {
      await onDelete();
    } catch (err) {
      setDeleteError((err as Error).message);
    }
  };

  const clickable = !!onOpenThread && !editing;

  return (
    <div
      className={`slack-message${selected ? ' selected' : ''}${clickable ? ' clickable' : ''}`}
      onClick={clickable ? onOpenThread : undefined}
    >
      <Avatar name={message.authorName} />
      <div className="slack-message-body">
        <div className="slack-message-meta">
          <strong>{message.authorName ?? '알 수 없음'}</strong>
          <span title={formatDateTime(message.createdAt)}>{formatTime(message.createdAt)}</span>
          {message.edited && <span>(수정됨)</span>}
          {message.syncFailed && <span className="badge badge-warning">동기화 실패</span>}
          <span className="spacer" />
          <span className="slack-message-actions" onClick={(e) => e.stopPropagation()}>
            {message.deepLink && !message.isOriginDeleted && (
              <a href={message.deepLink} target="_blank" rel="noreferrer">
                Slack에서 보기
              </a>
            )}
            {message.mine && !message.isOriginDeleted && !editing && (
              <>
                <button type="button" className="link-button" onClick={() => setEditing(true)}>
                  수정
                </button>
                <button type="button" className="link-button" onClick={remove}>
                  삭제
                </button>
              </>
            )}
          </span>
        </div>

        {message.isOriginDeleted && <p className="small muted m-0">Slack에서 삭제된 메시지입니다. (보관된 사본)</p>}

        {editing ? (
          <div onClick={(e) => e.stopPropagation()}>
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
          </div>
        ) : (
          <p className={`pre slack-text${message.isOriginDeleted ? ' muted' : ''}`}>{message.text}</p>
        )}

        {message.attachments.map((a, i) => {
          const box = (
            <>
              <span className="slack-file-ext">{ext(a.name)}</span>
              <span className="min-w-0">
                <span className="block truncate text-xs font-bold">{a.name}</span>
                <span className="block text-[11px] text-[var(--muted)]">첨부 파일</span>
              </span>
            </>
          );
          return a.url ? (
            <a key={i} className="slack-file" href={a.url} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>
              {box}
            </a>
          ) : (
            <span key={i} className="slack-file">
              {box}
            </span>
          );
        })}

        {deleteError && <div className="alert alert-error">삭제하지 못했습니다. {deleteError}</div>}

        {onOpenThread && message.replyCount > 0 && (
          <span className="slack-reply-count">답글 {message.replyCount}개</span>
        )}
      </div>
    </div>
  );
}
