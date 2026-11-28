// AI 업무 보조 "새 후보 찾기" 진행 상태.
// 화면(컴포넌트) 안에 두면 다른 메뉴로 갔다 오는 순간 "살펴보는 중"과 결과 안내가 사라져 끊긴 것처럼 보인다.
// 요청 자체는 서버에서 끝까지 진행되므로, 상태를 화면 밖(모듈)에 두고 돌아왔을 때 이어서 보여 준다.
import { useCallback, useSyncExternalStore } from 'react';
import { detectCandidates, type DetectResultResponse } from './api';

export type DetectionOutcome =
  | { ok: true; result: DetectResultResponse; finishedAt: number }
  | { ok: false; message: string; finishedAt: number };

interface DetectionState {
  running: boolean;
  outcome: DetectionOutcome | null;
}

const IDLE: DetectionState = { running: false, outcome: null };
const states = new Map<string, DetectionState>();
const listeners = new Set<() => void>();

function set(workspaceId: string, next: DetectionState) {
  states.set(workspaceId, next);
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** 이미 찾는 중이면 새로 보내지 않는다. */
export function startDetection(workspaceId: string) {
  if (states.get(workspaceId)?.running) return;
  set(workspaceId, { running: true, outcome: null });
  detectCandidates(workspaceId).then(
    (result) => set(workspaceId, { running: false, outcome: { ok: true, result, finishedAt: Date.now() } }),
    (error: Error) =>
      set(workspaceId, { running: false, outcome: { ok: false, message: error.message, finishedAt: Date.now() } }),
  );
}

export function useDetection(workspaceId: string) {
  const state = useSyncExternalStore(subscribe, () => states.get(workspaceId) ?? IDLE);
  const start = useCallback(() => startDetection(workspaceId), [workspaceId]);
  return { ...state, start };
}
