import { useState, type FormEvent, type KeyboardEvent } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../../../api/client';

/**
 * 메시지 입력창. 전송에 실패하면 입력 내용을 그대로 두고 다시 보낼 수 있게 한다. (UI-04)
 * Enter: 전송, Shift+Enter: 줄바꿈
 */
export default function Composer({
  placeholder,
  initialText = '',
  submitLabel = '전송',
  onSubmit,
  onCancel,
}: {
  placeholder: string;
  initialText?: string;
  submitLabel?: string;
  onSubmit: (text: string) => Promise<void>;
  onCancel?: () => void;
}) {
  const { workspaceId = '' } = useParams();
  const [text, setText] = useState(initialText);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<ApiError | Error | null>(null);

  const send = async (e?: FormEvent) => {
    e?.preventDefault();
    if (!text.trim() || sending) return;
    setSending(true);
    setError(null);
    try {
      await onSubmit(text.trim());
      setText('');
    } catch (err) {
      setError(err as Error); // 입력 내용은 지우지 않는다.
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

  return (
    <form className="slack-composer stack" onSubmit={send}>
      {error && (
        <div className="alert alert-error">
          <span>
            {needsOwnConnection ? (
              <>
                {error.message} <Link to={`/workspaces/${workspaceId}/integrations`}>연동 관리로 이동</Link>
              </>
            ) : (
              `전송하지 못했습니다. ${error.message}`
            )}
          </span>
          {!needsOwnConnection && (
            <button type="button" className="btn btn-small" onClick={() => void send()} disabled={sending}>
              다시 시도
            </button>
          )}
        </div>
      )}
      <textarea
        className="input"
        placeholder={placeholder}
        value={text}
        maxLength={4000}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        disabled={sending}
      />
      <div className="row">
        <span className="small muted">Enter 전송 · Shift+Enter 줄바꿈</span>
        <span className="spacer" />
        {onCancel && (
          <button type="button" className="btn btn-small" onClick={onCancel} disabled={sending}>
            취소
          </button>
        )}
        <button className="btn btn-primary btn-small" disabled={sending || !text.trim()}>
          {sending ? '보내는 중...' : submitLabel}
        </button>
      </div>
    </form>
  );
}
