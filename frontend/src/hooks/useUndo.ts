// 글쓰기 화면의 되돌리기(Ctrl+Z) · 다시 실행(Ctrl+Shift+Z, Ctrl+Y).
// 저장한 내용으로 돌아가는 게 아니라, 편집하면서 바꾼 한 단계 전으로 돌아간다.
// 같은 칸에 이어서 치는 글자는 한 단계로 묶는다. (잠시 멈추거나 오래 치면 새 단계)
import { useCallback, useRef, type KeyboardEvent as ReactKeyboardEvent } from 'react';

const IDLE_MS = 1000;
const MAX_GROUP_MS = 3000;
const MAX_STEPS = 200;

export function useUndo<T>(value: T, restore: (snapshot: T) => void) {
  const current = useRef(value);
  current.current = value;
  const restoreRef = useRef(restore);
  restoreRef.current = restore;
  const history = useRef({ past: [] as T[], future: [] as T[], group: null as string | null, startedAt: 0, lastAt: 0 });

  /**
   * 값을 바꾸기 직전에 부른다. group이 같고 이어서 치는 중이면 앞 단계에 묶는다.
   * (글자 입력은 칸마다 group을 주고, 행 추가·이미지 삭제처럼 한 번에 끝나는 변경은 group 없이 부른다)
   */
  const record = useCallback((group?: string) => {
    const h = history.current;
    const now = Date.now();
    if (group && h.group === group && now - h.lastAt < IDLE_MS && now - h.startedAt < MAX_GROUP_MS) {
      h.lastAt = now;
      return;
    }
    h.past.push(current.current);
    if (h.past.length > MAX_STEPS) h.past.shift();
    h.future = [];
    h.group = group ?? null;
    h.startedAt = now;
    h.lastAt = now;
  }, []);

  const reset = useCallback(() => {
    history.current = { past: [], future: [], group: null, startedAt: 0, lastAt: 0 };
  }, []);

  /**
   * 폼에 달면 안쪽 모든 입력 칸의 Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y를 이 기록으로 처리한다.
   * window keydown에 직접 달아도 된다. (입력 칸이 없는 시간표 등)
   */
  const onKeyDown = useCallback(
    (e: ReactKeyboardEvent | KeyboardEvent) => {
      const composing = 'nativeEvent' in e ? e.nativeEvent.isComposing : e.isComposing;
      if (!(e.ctrlKey || e.metaKey) || e.altKey || composing) return;
      const key = e.key.toLowerCase();
      if (key !== 'z' && key !== 'y') return;
      // 브라우저 기본 되돌리기는 칸 하나만 알고 화면 상태와 어긋나므로 막는다.
      e.preventDefault();
      const redo = key === 'y' || e.shiftKey;
      const h = history.current;
      const snapshot = (redo ? h.future : h.past).pop();
      if (snapshot === undefined) return;
      (redo ? h.past : h.future).push(current.current);
      h.group = null;
      restoreRef.current(snapshot);
    },
    [],
  );

  return { record, reset, onKeyDown };
}
