import { useEffect, useRef, useState } from 'react';
import type { SlackMessageResponse } from '../api';
import Avatar from '../../../components/Avatar';
import Dialog from '../../../components/Dialog';
import Icon from '../../../components/Icon';
import { formatTime } from '../../../utils/date';

/**
 * 메시지 한 건: 작성자 아바타, 작성 시각, 본문, 첨부 파일, 답글 수, 수정·삭제 메뉴(점 3개) (UI-04)
 *
 * @param layout channel: 채널 메시지 목록, original/reply: 스레드 패널 안
 */
export default function MessageItem({
  message,
  layout,
  selected,
  onOpenThread,
  onUpdate,
  onDelete,
}: {
  message: SlackMessageResponse;
  layout: 'channel' | 'original' | 'reply';
  selected?: boolean;
  onOpenThread?: () => void;
  onUpdate: (text: string) => Promise<void>;
  onDelete: () => Promise<void>;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(message.text);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const close = (e: MouseEvent) => !menuRef.current?.contains(e.target as Node) && setMenuOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [menuOpen]);

  const save = async () => {
    if (!draft.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await onUpdate(draft.trim());
      setEditing(false);
    } catch (e) {
      setError(`수정하지 못했어요. ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    setError(null);
    try {
      await onDelete();
      setConfirmDelete(false);
    } catch (e) {
      setConfirmDelete(false);
      setError(`삭제하지 못했어요. ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const canEdit = message.mine && !message.isOriginDeleted;
  const hasMenu = canEdit || (message.deepLink && !message.isOriginDeleted);

  const body = (
    <>
      <div className="message-author">
        <strong>{message.authorName ?? '알 수 없음'}</strong>
        <span>{formatTime(message.createdAt)}</span>
        {message.edited && <span className="edited">(수정됨)</span>}
        {message.syncFailed && <span className="status-tag warning">동기화 실패</span>}
      </div>
      {editing ? (
        <div className="message-edit" onClick={(e) => e.stopPropagation()}>
          <textarea value={draft} maxLength={4000} onChange={(e) => setDraft(e.target.value)} autoFocus />
          <div>
            <button type="button" className="ui-button secondary" onClick={() => setEditing(false)} disabled={busy}>
              취소
            </button>
            <button type="button" className="ui-button primary" onClick={save} disabled={busy || !draft.trim()}>
              저장
            </button>
          </div>
        </div>
      ) : message.isOriginDeleted ? (
        <p>Slack에서 삭제된 메시지입니다. (보관된 사본) {message.text}</p>
      ) : (
        <p>{message.text}</p>
      )}
      {message.attachments.map((a, i) => (
        <a
          key={i}
          className={layout === 'channel' ? 'message-attachment' : 'thread-file'}
          href={a.url ?? undefined}
          target="_blank"
          rel="noreferrer"
          onClick={(e) => e.stopPropagation()}
        >
          <span>
            <Icon name="paperclip" size={16} />
          </span>
          <div>
            <strong>{a.name}</strong>
            <small>Slack 첨부 파일</small>
          </div>
        </a>
      ))}
      {layout === 'channel' && message.replyCount > 0 && (
        <button
          type="button"
          className="reply-count"
          onClick={(e) => {
            e.stopPropagation();
            onOpenThread?.();
          }}
        >
          {message.replyCount}개의 답글 <Icon name="arrow" size={13} />
        </button>
      )}
      {error && <div className="notice error">{error}</div>}
    </>
  );

  const menu = hasMenu && !editing && (
    <div className="message-menu-wrap" ref={menuRef}>
      <button
        type="button"
        className="message-more"
        aria-label="메시지 메뉴"
        onClick={(e) => {
          e.stopPropagation();
          setMenuOpen((o) => !o);
        }}
      >
        •••
      </button>
      {menuOpen && (
        <div className="message-menu" onClick={(e) => e.stopPropagation()}>
          {canEdit && (
            <button
              type="button"
              onClick={() => {
                setDraft(message.text);
                setEditing(true);
                setMenuOpen(false);
              }}
            >
              <Icon name="edit" size={14} />
              수정
            </button>
          )}
          {canEdit && (
            <button
              type="button"
              className="danger"
              onClick={() => {
                setConfirmDelete(true);
                setMenuOpen(false);
              }}
            >
              <Icon name="trash" size={14} />
              삭제
            </button>
          )}
          {message.deepLink && (
            <a href={message.deepLink} target="_blank" rel="noreferrer">
              <Icon name="external" size={14} />
              Slack에서 보기
            </a>
          )}
        </div>
      )}
    </div>
  );

  const deleteDialog = confirmDelete && (
    <Dialog
      title="메시지 삭제"
      onClose={() => setConfirmDelete(false)}
      footer={
        <>
          <button type="button" className="ui-button secondary" onClick={() => setConfirmDelete(false)} disabled={busy}>
            취소
          </button>
          <button type="button" className="ui-button danger" onClick={remove} disabled={busy}>
            삭제
          </button>
        </>
      }
    >
      <div className="delete-copy">
        <div className="delete-icon">
          <Icon name="trash" />
        </div>
        <div>
          <strong>이 메시지를 삭제할까요?</strong>
          <span>Slack에서도 삭제됩니다. ToGather에는 근거 발언·Task 연결을 위해 사본이 남습니다.</span>
        </div>
      </div>
    </Dialog>
  );

  if (layout !== 'channel') {
    return (
      <div className={layout === 'original' ? 'thread-original' : 'thread-reply'}>
        <Avatar name={message.authorName} toneKey={message.authorId} />
        <div>{body}</div>
        {menu}
        {deleteDialog}
      </div>
    );
  }

  return (
    <article
      className={`chat-message${selected ? ' selected' : ''}${message.isOriginDeleted ? ' deleted' : ''}`}
      onClick={onOpenThread}
    >
      <Avatar name={message.authorName} toneKey={message.authorId} />
      <div className="message-content">{body}</div>
      {menu}
      {deleteDialog}
    </article>
  );
}
