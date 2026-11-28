import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { linkEvidence, unlinkEvidence, ITEM_TYPE_TEXT, type AnalysisResultDTO } from '../api';
import { formatDateTime } from '../../../utils/date';
import AnalysisIcon from './AnalysisIcon';

export default function EvidenceDrawer({
  workspaceId,
  data,
  itemId,
  editable,
  loading,
  onChanged,
  onClose,
}: {
  workspaceId: string;
  data: AnalysisResultDTO;
  itemId: number | null;
  editable: boolean;
  loading: boolean;
  onChanged: () => void;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const item = data.items.find((item) => item.itemId === itemId);
  const linked = new Set(item?.evidences.map((e) => e.contentRefId) ?? []);
  const candidates = data.messages.filter((message) => !linked.has(message.contentRefId));
  const disabled = working || loading;

  useEffect(() => {
    const element = dialog.current;
    const previousFocus = document.activeElement;
    const previousOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';
    if (element && !element.open) element.showModal();
    return () => {
      if (element?.open) element.close();
      document.documentElement.style.overflow = previousOverflow;
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected)
        previousFocus.focus({ preventScroll: true });
    };
  }, []);

  const run = async (action: () => Promise<unknown>) => {
    setWorking(true);
    setError(null);
    try {
      await action();
      onChanged();
    } catch (error) {
      setError((error as Error).message);
    } finally {
      setWorking(false);
    }
  };

  return (
    <dialog
      ref={dialog}
      className="analysis-evidence-drawer"
      aria-labelledby="analysis-evidence-title"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          const rect = event.currentTarget.getBoundingClientRect();
          if (
            event.clientX < rect.left ||
            event.clientX > rect.right ||
            event.clientY < rect.top ||
            event.clientY > rect.bottom
          )
            onClose();
        }
      }}
    >
      <header className="analysis-drawer-head">
        <div>
          <span className="analysis-eyebrow">원문과 함께 확인하기</span>
          <h2 id="analysis-evidence-title">{item ? '항목의 근거' : '스레드 원문'}</h2>
        </div>
        <button type="button" className="analysis-icon-button" aria-label="근거 패널 닫기" autoFocus onClick={onClose}>
          <AnalysisIcon name="close" />
        </button>
      </header>
      <div className="analysis-drawer-body">
        {item && (
          <div className="analysis-evidence-context">
            <span>{ITEM_TYPE_TEXT[item.itemType]}</span>
            <p>{item.content}</p>
          </div>
        )}
        {error && (
          <div className="alert alert-error" role="alert">
            {error}
          </div>
        )}
        {item ? (
          <>
            <h3 className="analysis-drawer-section-title">
              연결된 발언 <span>{item.evidences.length}</span>
            </h3>
            {!item.evidences.length && (
              <p className="analysis-subtle">
                연결된 근거가 없어요.{editable && ' 아래에서 관련 발언을 연결해 주세요.'}
              </p>
            )}
            {item.evidences.map((evidence) => (
              <article className="analysis-evidence-entry" key={evidence.evidenceId}>
                <div className="analysis-evidence-meta">
                  <strong>{evidence.authorName ?? '알 수 없음'}</strong>
                  <span>{evidence.createdAt && formatDateTime(evidence.createdAt)}</span>
                  {editable && (
                    <button
                      type="button"
                      className="analysis-text-button"
                      disabled={disabled}
                      onClick={() => void run(() => unlinkEvidence(workspaceId, evidence.evidenceId))}
                    >
                      해제
                    </button>
                  )}
                </div>
                <p className="pre">{evidence.text}</p>
                {evidence.isOriginDeleted && <span className="analysis-meta-pill">원본 삭제됨 · 보관된 발언</span>}
                {evidence.deepLink && (
                  <a className="analysis-evidence-link" href={evidence.deepLink} target="_blank" rel="noreferrer">
                    Slack에서 보기 ↗
                  </a>
                )}
              </article>
            ))}
            {editable && (
              <details className="analysis-link-candidates" open={item.evidences.length === 0}>
                <summary>
                  다른 발언 연결 <span>{candidates.length}</span>
                  <AnalysisIcon name="chevron" className="h-3.5 w-3.5" />
                </summary>
                {!candidates.length && <p className="analysis-subtle">모든 발언이 연결되어 있어요.</p>}
                {candidates.map((message) => (
                  <article className="analysis-evidence-entry" key={message.contentRefId}>
                    <div className="analysis-evidence-meta">
                      <strong>{message.authorName ?? '알 수 없음'}</strong>
                      <span>{message.createdAt && formatDateTime(message.createdAt)}</span>
                    </div>
                    <p className="pre">{message.text}</p>
                    <button
                      type="button"
                      className="analysis-text-button"
                      disabled={disabled}
                      onClick={() => void run(() => linkEvidence(workspaceId, item.itemId, message.contentRefId))}
                    >
                      <AnalysisIcon name="plus" className="h-3 w-3" />
                      근거로 연결
                    </button>
                  </article>
                ))}
              </details>
            )}
          </>
        ) : (
          <>
            <p className="analysis-subtle">AI가 읽은 대화와 이후에 추가된 발언을 확인해 보세요.</p>
            {!data.messages.length && <p className="analysis-subtle">표시할 대화가 없어요.</p>}
            {data.messages.map((message) => (
              <article className="analysis-evidence-entry" key={message.contentRefId}>
                <div className="analysis-evidence-meta">
                  <strong>{message.authorName ?? '알 수 없음'}</strong>
                  <span>{message.createdAt && formatDateTime(message.createdAt)}</span>
                </div>
                {!message.analyzed && <span className="analysis-meta-pill">분석 후 추가</span>}
                <p className="pre">{message.text}</p>
              </article>
            ))}
          </>
        )}
      </div>
      <footer className="analysis-drawer-foot">
        <Link to={`/workspaces/${workspaceId}/slack?channel=${data.channelId}`}>ToGather에서 대화 보기</Link>
        {data.threadLink && (
          <a href={data.threadLink} target="_blank" rel="noreferrer">
            Slack 원문 ↗
          </a>
        )}
      </footer>
    </dialog>
  );
}
