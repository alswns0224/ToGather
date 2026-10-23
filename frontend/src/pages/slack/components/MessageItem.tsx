import { useState } from 'react';
import type { SlackMessageResponse } from '../api';
import { formatDateTime } from '../../../utils/date';
import Composer from './Composer';

const AVATAR_COLORS = ['#4F46E5', '#7C3AED', '#0D9488', '#E11D48', '#2563EB', '#D97706'];

/** 이름마다 늘 같은 아바타 색 */
function avatarColor(name: string | null) {
  const s = name ?? '';
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

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
        <span
          className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full text-[10px] font-bold text-white ring-2 ring-white dark:ring-slate-900"
          style={{ background: avatarColor(message.authorName) }}
        >
          {(message.authorName ?? '?').slice(0, 2)}
        </span>
        <strong className="text-xs">{message.authorName ?? '알 수 없음'}</strong>
        <span className="text-[10px] muted">{formatDateTime(message.createdAt)}</span>
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
        <div className="mt-1 flex justify-end border-0 border-t border-solid border-slate-200/40 pt-2 dark:border-white/10">
          <button className="link-button text-[11px] font-bold" onClick={onOpenThread}>
            {message.replyCount > 0 ? `답글 ${message.replyCount}개 →` : '스레드 열기 →'}
          </button>
        </div>
      )}
    </li>
  );
}
