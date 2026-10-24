import { useEffect, useRef } from 'react';

export interface ChangeEvent {
  provider: string;
  targetId: string;
}

/**
 * 외부 서비스에서 바뀐 내용을 서버가 바로 알려준다 (Server-Sent Events).
 * 연결이 끊기면 브라우저가 알아서 다시 연결한다.
 */
export function useWorkspaceEvents(workspaceId: string, onChange: (event: ChangeEvent) => void) {
  const handler = useRef(onChange);
  handler.current = onChange;

  useEffect(() => {
    if (!workspaceId || typeof EventSource === 'undefined') return;
    const source = new EventSource(`/api/workspaces/${workspaceId}/events`);
    source.addEventListener('change', (e) => {
      try {
        handler.current(JSON.parse((e as MessageEvent).data) as ChangeEvent);
      } catch {
        // 형식이 다른 이벤트는 무시한다.
      }
    });
    return () => source.close();
  }, [workspaceId]);
}
