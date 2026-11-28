// "논의 분석하기" 진행 상태. 화면(컴포넌트) 안에 두면 다른 메뉴로 갔다 오는 순간 진행 단계가 사라지고,
// 돌아왔을 때 분석을 다시 요청해 "이미 분석 중" 오류가 날 수 있다. 서버는 분석을 끝까지 하므로
// 상태를 화면 밖(모듈)에 두고 이어서 보여 준다. 새로고침으로 기억이 없어져도 서버의 "분석 중" 기록을 보고 기다린다.
import { useSyncExternalStore } from 'react';
import { ApiError } from '../../api/client';
import { analyzeThread, getAnalysis, getLatestAnalysisId } from './api';

export type AnalysisRun =
  | { state: 'running'; workspaceId: string; threadId: number; startedAt: number }
  | { state: 'done'; workspaceId: string; threadId: number; startedAt: number; analysisId: number; seen: boolean }
  | { state: 'failed'; workspaceId: string; threadId: number; startedAt: number; message: string; seen: boolean };

const POLL_MS = 3000;
/** 서버의 "분석 중" 기록을 기다리는 최대 시간 (분석 제한 시간보다 넉넉히) */
const MAX_WAIT_MS = 4 * 60 * 1000;

const runs = new Map<string, AnalysisRun>();
const listeners = new Set<() => void>();
let snapshot: AnalysisRun[] = [];

const keyOf = (workspaceId: string, threadId: number) => `${workspaceId}:${threadId}`;

function set(run: AnalysisRun) {
  runs.set(keyOf(run.workspaceId, run.threadId), run);
  snapshot = [...runs.values()];
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getRun(workspaceId: string, threadId: number) {
  return runs.get(keyOf(workspaceId, threadId));
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** 서버에 남은 분석이 "분석 중"인 동안 기다렸다가 결과를 기록한다. */
async function waitForServer(workspaceId: string, threadId: number, startedAt: number) {
  const deadline = Date.now() + MAX_WAIT_MS;
  while (Date.now() < deadline) {
    const id = await getLatestAnalysisId(workspaceId, threadId);
    if (id != null) {
      const analysis = await getAnalysis(workspaceId, id);
      if (analysis.status === 'FAILED') {
        throw new Error('AI 분석에 실패했습니다. 다시 시도해 주세요.');
      }
      if (analysis.status !== 'ANALYZING') {
        set({ state: 'done', workspaceId, threadId, startedAt, analysisId: id, seen: false });
        return;
      }
    }
    await sleep(POLL_MS);
  }
  throw new Error('분석이 오래 걸리고 있어요. 잠시 뒤 논의 정리 목록에서 확인해 주세요.');
}

function track(workspaceId: string, threadId: number, startedAt: number, work: Promise<void>) {
  work.catch((error: Error) =>
    set({ state: 'failed', workspaceId, threadId, startedAt, message: error.message, seen: false }),
  );
}

/** 분석을 시작한다. 이미 진행 중이면 새로 보내지 않는다. */
export function startAnalysis(workspaceId: string, threadId: number) {
  if (getRun(workspaceId, threadId)?.state === 'running') return;
  const startedAt = Date.now();
  set({ state: 'running', workspaceId, threadId, startedAt });
  track(workspaceId, threadId, startedAt,
    analyzeThread(workspaceId, threadId).then(
      (result) => set({ state: 'done', workspaceId, threadId, startedAt, analysisId: result.analysisId, seen: false }),
      (error: unknown) => {
        // 다른 탭·기기에서 같은 스레드를 분석하고 있으면 그 결과를 기다린다.
        if (error instanceof ApiError && error.code === 'ANALYSIS_IN_PROGRESS') {
          return waitForServer(workspaceId, threadId, startedAt);
        }
        throw error;
      },
    ));
}

/**
 * 화면에 기억이 없을 때(새로고침 등): 서버의 최신 분석이 "분석 중"이면 다시 요청하지 않고 기다리고,
 * 아니면 분석을 시작한다.
 */
export function resumeOrStart(workspaceId: string, threadId: number) {
  if (getRun(workspaceId, threadId)) return;
  const startedAt = Date.now();
  set({ state: 'running', workspaceId, threadId, startedAt });
  getLatestAnalysisId(workspaceId, threadId)
    .then((id) => (id == null ? null : getAnalysis(workspaceId, id)))
    .then((latest) => {
      if (latest?.status === 'ANALYZING') {
        track(workspaceId, threadId, startedAt, waitForServer(workspaceId, threadId, startedAt));
      } else {
        runs.delete(keyOf(workspaceId, threadId));
        startAnalysis(workspaceId, threadId);
      }
    })
    .catch(() => {
      runs.delete(keyOf(workspaceId, threadId));
      startAnalysis(workspaceId, threadId);
    });
}

/** 다시 시도: 실패 기록을 지우고 새로 시작 */
export function retryAnalysis(workspaceId: string, threadId: number) {
  runs.delete(keyOf(workspaceId, threadId));
  startAnalysis(workspaceId, threadId);
}

/** 결과를 봤거나 알림을 닫았다. (알림에 다시 나오지 않는다) */
export function markSeen(workspaceId: string, threadId: number) {
  const run = getRun(workspaceId, threadId);
  if (run && run.state !== 'running' && !run.seen) set({ ...run, seen: true });
}

export function useAnalysisRun(workspaceId: string, threadId: number) {
  return useSyncExternalStore(subscribe, () => runs.get(keyOf(workspaceId, threadId)));
}

/** 이 워크스페이스의 모든 분석 진행 상태 (끝났다는 알림용) */
export function useAnalysisRuns(workspaceId: string) {
  const all = useSyncExternalStore(subscribe, () => snapshot);
  return all.filter((run) => run.workspaceId === workspaceId);
}
