import { useEffect } from 'react';
import { createReply, deleteMessage, getThread, updateMessage, type SlackMessageResponse } from '../api';
import { useAsync } from '../../../hooks/useAsync';
import { ErrorBox, Loading } from '../../../components/Status';
import { BTN_AI } from '../../../components/ui';
import Composer from './Composer';
import MessageItem from './MessageItem';

/** 스레드: root와 답글, 논의 분석 진입, 답글 작성 (UI-04, 시안의 오른쪽 칸) */
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

  const root = thread.data?.root;

  return (
    <aside className="slack-thread">
      <div className="slack-col-head">
        <strong className="text-[15px]">스레드</strong>
        <button type="button" className="slack-close" onClick={onClose} aria-label="스레드 닫기">
          ✕
        </button>
      </div>

      <div className="slack-thread-body">
        {thread.loading && !thread.data && <Loading />}
        {thread.error && <ErrorBox error={thread.error} onRetry={thread.reload} />}
        {thread.data && root && (
          <>
            {thread.data.refreshFailed && (
              <div className="alert alert-error small">
                Slack에서 최신 답글을 가져오지 못해 저장된 내용을 보여줍니다.
                <button type="button" className="btn btn-small" onClick={thread.reload}>
                  다시 시도
                </button>
              </div>
            )}
            <div className="slack-thread-root">
              <MessageItem
                message={root}
                onUpdate={async (text) => {
                  await updateMessage(workspaceId, channelId, root.ts, text);
                  changed();
                }}
                onDelete={async () => {
                  await deleteMessage(workspaceId, channelId, root.ts);
                  changed();
                }}
              />
            </div>

            <div className="text-xs font-bold text-[var(--muted)]">답글 {thread.data.replies.length}개</div>

            <div className="flex flex-col gap-1">
              {thread.data.replies.length === 0 ? (
                <div className="text-xs text-[var(--muted)]">아직 답글이 없습니다.</div>
              ) : (
                thread.data.replies.map((r) => (
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
                ))
              )}
            </div>

            {onAnalyze && !root.isOriginDeleted && (
              <div className="slack-analyze">
                <div className="flex items-center gap-1.5 text-sm font-extrabold">
                  <span className="text-[var(--primary)]">✦</span>
                  <span>이 대화에서 다음 작업 찾기</span>
                </div>
                <div className="text-xs text-[var(--muted)]">결정사항·할 일·회의 제안을 정리해요.</div>
                <button
                  type="button"
                  className={`${BTN_AI} !h-[38px] w-full`}
                  onClick={() => onAnalyze(root)}
                  disabled={!root.contentRefId}
                >
                  논의 분석하기
                </button>
                <div className="text-center text-[11px] text-[var(--muted)]">ⓘ 분석 결과는 검토 후 반영해요.</div>
              </div>
            )}
          </>
        )}
      </div>

      {root && !root.isOriginDeleted && (
        <div className="slack-col-foot">
          <Composer
            compact
            placeholder="스레드에 답글 남기기"
            onSubmit={async (text) => {
              await createReply(workspaceId, channelId, ts, text);
              changed();
            }}
          />
        </div>
      )}
    </aside>
  );
}
