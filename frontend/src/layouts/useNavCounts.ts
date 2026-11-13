// 왼쪽 메뉴 옆 숫자 (팀원 UI 시안의 nav-count). 하나가 실패해도 나머지는 보여준다.
import { useEffect, useState } from 'react';
import { getChannels } from '../pages/slack/api';
import { getPages } from '../pages/notion/api';
import { getDriveFiles } from '../pages/drive/api';
import { getPendingAnalyses } from '../pages/analysis/api';
import { getTasks } from '../pages/task/api';
import { getSuggestions } from '../pages/assistant/api';
import { getEvents } from '../pages/calendar/api';
import { getMeetings } from '../pages/meeting/api';
import { toLocalIso } from '../utils/date';

export type NavCounts = Partial<Record<string, number>>;

/** 메뉴 경로(to) → 숫자. section이 바뀔 때(다른 메뉴로 이동) 다시 센다. */
export function useNavCounts(workspaceId: string, section: string): NavCounts {
  const [counts, setCounts] = useState<NavCounts>({});

  useEffect(() => {
    let alive = true;
    const now = new Date();
    const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
    const monthEnd = new Date(now.getFullYear(), now.getMonth() + 1, 1);

    const jobs: [string, () => Promise<number>][] = [
      ['slack', () => getChannels(workspaceId).then((r) => r.length)],
      ['notion', () => getPages(workspaceId).then((r) => r.length)],
      ['drive', () => getDriveFiles(workspaceId).then((r) => r.length)],
      ['slack/analyses', () => getPendingAnalyses(workspaceId).then((r) => r.length)],
      ['tasks', () => getTasks(workspaceId, 0, 1).then((r) => r.total)],
      ['assistant', () => getSuggestions(workspaceId).then((r) => r.filter((s) => s.status === 'PENDING').length)],
      ['calendar', () => getEvents(workspaceId, toLocalIso(monthStart), toLocalIso(monthEnd)).then((r) => r.length)],
      [
        'meetings',
        () =>
          getMeetings(workspaceId).then(
            (r) => r.filter((m) => !m.startAt || new Date(m.startAt).getTime() >= now.getTime()).length,
          ),
      ],
    ];

    jobs.forEach(([key, job]) =>
      job()
        .then((n) => alive && setCounts((c) => ({ ...c, [key]: n })))
        .catch(() => undefined),
    );
    return () => {
      alive = false;
    };
  }, [workspaceId, section]);

  return counts;
}
