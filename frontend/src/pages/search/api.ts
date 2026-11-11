import { http, ws } from '../../api/client';
import type { Provider } from '../integration/api';
import type { TaskSummary } from '../task/api';

/** 통합 검색 대상 서비스 */
export type SearchProvider = Extract<Provider, 'SLACK' | 'NOTION' | 'GOOGLE_DRIVE'>;

export interface SearchItem {
  contentRefId: number;
  provider: SearchProvider;
  title: string | null;
  snippet: string;
  deepLink: string | null;
  createdAt: string | null;
  authorName: string | null;
  /** 관련도 (0~1) */
  score: number;
  /** /workspaces/{id} 뒤에 붙여 ToGather 안에서 여는 경로 */
  appPath: string;
  tasks: TaskSummary[];
}

export interface SearchResponse {
  query: string;
  items: SearchItem[];
  /** 아직 검색에 반영되지 않은 콘텐츠 수 */
  pending: number;
}

export const search = (workspaceId: string, q: string, providers: SearchProvider[]) => {
  const params = new URLSearchParams({ q });
  if (providers.length > 0) params.set('providers', providers.join(','));
  return http.get<SearchResponse>(ws(workspaceId, `/search?${params}`));
};
