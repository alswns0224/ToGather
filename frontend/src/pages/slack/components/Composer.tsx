import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../../../api/client';
import { BTN_AI, BTN_SUB } from '../../../components/ui';
import { MAX_DRIVE_UPLOAD_SIZE, uploadDriveFile } from '../../drive/api';
import { DriveFolderList, formatFileSize, useDriveUploadFolders } from '../../drive/DriveUploadFolders';

/** Slack 링크 표기(<주소|이름>)에서 깨지는 문자를 막는다. */
const slackLabel = (name: string) => name.replace(/[<>|]/g, ' ');

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
  compact = false,
  attachToDrive = false,
}: {
  placeholder: string;
  /** 스레드 답글처럼 한 줄 입력 + 전송 버튼만 */
  compact?: boolean;
  initialText?: string;
  submitLabel?: string;
  onSubmit: (text: string) => Promise<void>;
  onCancel?: () => void;
  /**
   * 파일 첨부: 고른 파일은 전송할 때 Google Drive(고른 폴더)에 올리고, 메시지에 Drive 링크를 붙여 보낸다.
   * 전송하지 않으면 Drive에도 올리지 않는다.
   */
  attachToDrive?: boolean;
}) {
  const { workspaceId = '' } = useParams();
  const [text, setText] = useState(initialText);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [pickingFolder, setPickingFolder] = useState(false);
  const [dragging, setDragging] = useState(false);
  // Drive에는 올렸는데 메시지 전송이 실패하면, 다시 보낼 때 또 올리지 않는다.
  const [uploaded, setUploaded] = useState<{ key: string; link: string; name: string } | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const folders = useDriveUploadFolders(workspaceId, attachToDrive && file !== null);

  const chooseFile = (chosen: File | null) => {
    setFileError(null);
    setUploaded(null);
    if (!chosen) return;
    if (chosen.size === 0) { setFileError('빈 파일은 첨부할 수 없어요.'); return; }
    if (chosen.size > MAX_DRIVE_UPLOAD_SIZE) { setFileError('100MB 이하 파일만 첨부할 수 있어요.'); return; }
    setFile(chosen);
  };

  const send = async (e?: FormEvent) => {
    e?.preventDefault();
    if ((!text.trim() && !file) || sending) return;
    setSending(true);
    setError(null);
    try {
      let body = text.trim();
      if (file) {
        if (!folders.folderId) throw new Error('파일을 올릴 Drive 폴더를 골라 주세요.');
        const key = `${folders.folderId}:${file.name}:${file.size}:${file.lastModified}`;
        let link = uploaded?.key === key ? uploaded : null;
        if (!link) {
          const result = await uploadDriveFile(workspaceId, folders.folderId, file);
          if (!result.deepLink) throw new Error('Drive에 올린 파일의 링크를 확인하지 못했어요.');
          link = { key, link: result.deepLink, name: result.title ?? file.name };
          setUploaded(link);
          folders.remember();
        }
        const attachment = `📎 <${link.link}|${slackLabel(link.name)}> (Google Drive)`;
        body = body ? `${body}\n${attachment}` : attachment;
      }
      await onSubmit(body);
      setText('');
      setFile(null);
      setUploaded(null);
    } catch (err) {
      setError(err as Error); // 입력 내용과 첨부는 지우지 않는다.
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

  const needsOwnConnection = error instanceof ApiError && (error.code === 'SLACK_OWN_CONNECTION_REQUIRED' || error.code === 'OWN_CONNECTION_REQUIRED');

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
              `${submitLabel}하지 못했습니다. ${error.message}`
            )}
          </span>
          {!needsOwnConnection && (
            <button type="button" className="btn btn-small" onClick={() => void send()} disabled={sending}>
              다시 시도
            </button>
          )}
        </div>
      )}
      <div
        className={`slack-composer-box${compact ? ' compact' : ''}${dragging ? ' dragging' : ''}`}
        // 파일을 입력창에 끌어 놓으면 첨부한다. (전송할 때 Drive에 올라간다)
        onDragOver={attachToDrive ? (e) => {
          if (!e.dataTransfer.types.includes('Files')) return;
          e.preventDefault();
          if (!sending) setDragging(true);
        } : undefined}
        onDragLeave={attachToDrive ? (e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragging(false);
        } : undefined}
        onDrop={attachToDrive ? (e) => {
          if (!e.dataTransfer.types.includes('Files')) return;
          e.preventDefault();
          setDragging(false);
          if (!sending) chooseFile(e.dataTransfer.files?.[0] ?? null);
        } : undefined}
      >
        {dragging && <div className="slack-drop-hint">여기에 놓으면 첨부돼요 · 전송할 때 Drive에 올라가요</div>}
        <textarea
          placeholder={placeholder}
          value={text}
          rows={compact ? 1 : 2}
          maxLength={4000}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKeyDown}
          disabled={sending}
        />
        {attachToDrive && file && (
          <div className="slack-attachment">
            <span className="slack-attachment-file" title={file.name}>
              📎 {file.name} <span className="muted">· {formatFileSize(file.size)}</span>
            </span>
            <button type="button" className="slack-attachment-folder" disabled={sending || !folders.rows?.length}
              onClick={() => setPickingFolder(true)} title={folders.selected?.path ?? '저장할 폴더 고르기'}>
              📁 {folders.selected?.name ?? (folders.rows?.length === 0 ? '연동된 폴더 없음' : '폴더 고르기')} ▾
            </button>
            {uploaded && <span className="small muted">Drive에 올림 · 전송만 다시 해요</span>}
            <button type="button" className="slack-attachment-remove" aria-label="첨부 빼기" disabled={sending}
              onClick={() => { setFile(null); setUploaded(null); }}>✕</button>
          </div>
        )}
        <div className="slack-composer-bar">
          {attachToDrive && (
            <>
              <input ref={inputRef} type="file" className="hidden" aria-label="첨부할 파일 선택" disabled={sending}
                onChange={(e) => { chooseFile(e.target.files?.[0] ?? null); e.target.value = ''; }} />
              <button type="button" className={`${BTN_SUB} !h-[30px] !px-2.5 !text-xs`} disabled={sending}
                title="전송할 때 Google Drive에 올리고 링크를 붙여요" onClick={() => inputRef.current?.click()}>
                📎{compact ? '' : ' 파일'}
              </button>
            </>
          )}
          {!compact && <span className="small muted">Enter {submitLabel} · Shift+Enter 줄바꿈</span>}
          <span className="spacer" />
          {onCancel && (
            <button type="button" className={`${BTN_SUB} !h-[30px] !px-3 !text-xs`} onClick={onCancel} disabled={sending}>
              취소
            </button>
          )}
          <button
            className={`${compact ? BTN_SUB : BTN_AI} !h-[30px] !px-3 !text-xs`}
            disabled={sending || (!text.trim() && !file)}
          >
            {sending
              ? submitLabel === '저장' ? '저장 중...' : file && !uploaded ? 'Drive에 올리는 중...' : '보내는 중...'
              : submitLabel}
          </button>
        </div>
      </div>
      {fileError && <p role="alert" className="m-0 text-xs text-[var(--danger)]">{fileError}</p>}
      {attachToDrive && file && folders.error && <p role="alert" className="m-0 text-xs text-[var(--danger)]">{folders.error}</p>}
      {pickingFolder && folders.rows && (
        <div className="modal-backdrop" onClick={() => setPickingFolder(false)}>
          <div role="dialog" aria-modal="true" aria-label="저장할 폴더 고르기"
            className="w-full max-w-[420px] rounded-[18px] border border-[var(--border)] bg-[var(--surface)] p-4 shadow-[var(--shadow)]"
            onClick={(e) => e.stopPropagation()}>
            <strong className="text-[15px]">첨부 파일을 올릴 Drive 폴더</strong>
            <p className="m-0 mb-3 mt-1 text-xs text-[var(--muted)]">전송할 때 이 폴더에 올라가요. 전송하지 않으면 올리지 않아요.</p>
            <DriveFolderList rows={folders.rows} value={folders.folderId}
              onChange={(id) => { folders.setFolderId(id); setUploaded(null); }} />
            {folders.selected && <p className="m-0 mt-1.5 text-[11px] text-[var(--muted)]">저장 위치: {folders.selected.path}</p>}
            <div className="mt-3 flex justify-end">
              <button type="button" className="btn btn-primary btn-small" onClick={() => setPickingFolder(false)}>확인</button>
            </div>
          </div>
        </div>
      )}
    </form>
  );
}
