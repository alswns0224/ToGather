import { useState, type KeyboardEvent } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../../../api/client';
import Icon from '../../../components/Icon';

/**
 * 메시지 입력창 (UI-04). 전송에 실패하면 입력 내용을 그대로 두고 다시 보낼 수 있게 한다.
 * Enter: 전송, Shift+Enter: 줄바꿈
 *
 * @param variant message: 채널 하단 입력창, thread: 스레드 답글 입력창
 */
export default function Composer({
  variant,
  placeholder,
  onSubmit,
}: {
  variant: 'message' | 'thread';
  placeholder: string;
  onSubmit: (text: string) => Promise<void>;
}) {
  const { workspaceId = '' } = useParams();
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const send = async () => {
    if (!text.trim() || sending) return;
    setSending(true);
    setError(null);
    try {
      await onSubmit(text.trim());
      setText('');
    } catch (e) {
      setError(e as Error); // 입력 내용은 지우지 않는다.
    } finally {
      setSending(false);
    }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void send();
    }
  };

  const needsOwnConnection = error instanceof ApiError && error.code === 'SLACK_OWN_CONNECTION_REQUIRED';
  const errorBox = error && (
    <div className={`notice error ${variant === 'message' ? 'composer-error' : ''}`} role="alert">
      <Icon name="alert" size={16} />
      {needsOwnConnection ? (
        <span>
          {error.message} <Link to={`/workspaces/${workspaceId}/integrations`}>서비스 연동으로 이동</Link>
        </span>
      ) : (
        <>
          <span>전송하지 못했어요. {error.message}</span>
          <button type="button" className="ui-button secondary" onClick={() => void send()} disabled={sending}>
            다시 시도
          </button>
        </>
      )}
    </div>
  );

  if (variant === 'thread') {
    return (
      <div className="thread-footer">
        {errorBox}
        <div className="thread-input">
          <textarea
            value={text}
            maxLength={4000}
            placeholder={placeholder}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKeyDown}
            disabled={sending}
          />
          <button type="button" className="ui-button primary" onClick={() => void send()} disabled={sending || !text.trim()}>
            {sending ? '보내는 중' : '답글'}
          </button>
        </div>
      </div>
    );
  }

  return (
    <>
      {errorBox}
      <div className="message-composer">
        <textarea
          value={text}
          maxLength={4000}
          placeholder={placeholder}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKeyDown}
          disabled={sending}
        />
        <div className="composer-actions">
          <span>Enter로 전송 · Shift+Enter로 줄바꿈</span>
          <button type="button" className="ui-button primary" onClick={() => void send()} disabled={sending || !text.trim()}>
            <Icon name="send" size={14} />
            {sending ? '보내는 중' : '보내기'}
          </button>
        </div>
      </div>
    </>
  );
}
