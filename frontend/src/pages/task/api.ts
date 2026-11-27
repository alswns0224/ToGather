import { api, http, ws } from '../../api/client';
import type { MeetingStatus } from '../meeting/api';

export type TaskStatus = 'IN_PROGRESS' | 'DONE';
export type RiskLevel = 'NORMAL' | 'WARNING' | 'DANGER';

export interface DeliverableInput {
  name: string;
  ext: string;
}

export interface TaskSummary {
  taskId: number;
  workspaceId: number;
  title: string;
  assigneeId: number | null;
  dueDate: string | null;
  status: TaskStatus;
  progress: number;
  riskLevel: RiskLevel;
}

export interface TaskDetail {
  taskId: number;
  workspaceId: number;
  title: string;
  description: string | null;
  assigneeId: number | null;
  dueDate: string | null;
  status: TaskStatus;
  progress: number;
  riskLevel: RiskLevel;
  statusManagedByDeliverables: boolean;
}

export type TaskReferenceProvider = 'SLACK' | 'NOTION' | 'GOOGLE_DRIVE';

export interface TaskReference {
  contentRefId: number;
  provider: string;
  title: string | null;
  content: string | null;
  deepLink: string | null;
}

export interface TaskReferenceCandidate {
  contentRefId: number;
  title: string | null;
  deepLink: string | null;
  lastModifiedAt: string | null;
}

export interface TaskDeliverable {
  deliverableId: number;
  name: string;
  ext: string | null;
  fileName: string | null;
  link: string | null;
  submitted: boolean;
  /** 제출한 Drive 파일 상태: TRASHED(휴지통으로 이동됨), DELETED(원본을 확인할 수 없음) */
  fileStatus?: 'TRASHED' | 'DELETED' | null;
}

export interface TaskContext {
  task: TaskDetail;
  contentsByProvider: Record<string, TaskReference[]>;
  deliverables: TaskDeliverable[];
  originDecision: {
    itemId: number;
    itemType: string;
    content: string;
    evidenceContentRefIds: number[];
  } | null;
  originEvidence: TaskReference[];
  meetings: {
    meetingId: number;
    title: string;
    status: MeetingStatus;
    startAt: string | null;
  }[];
  contentStates: Record<string, TaskContextContentState>;
  /** 내 계정으로 원본을 볼 수 없어 숨긴 연결 자료·근거 발언 수 */
  hiddenContentCount?: number;
}

export interface TaskContextContentState {
  createdAt: string | null;
  originDeleted: boolean;
  driveTrashed: boolean;
}

export interface TaskCreateRequest {
  title: string;
  description?: string;
  assigneeId?: number | null;
  dueDate?: string | null;
  status?: TaskStatus;
  deliverables?: DeliverableInput[];
}

export interface TaskUpdateRequest {
  title: string;
  description?: string;
  assigneeId?: number | null;
  dueDate?: string | null;
  status?: TaskStatus;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface TaskFilters {
  mine?: boolean;
  assigneeId?: number;
  unassigned?: boolean;
  status?: TaskStatus;
  query?: string;
  dueBefore?: string;
  sort?: 'RECENT' | 'DUE_DATE';
}

export interface TaskProgressSummary {
  totalCount: number;
  inProgressCount: number;
  doneCount: number;
  progress: number;
  normalCount: number;
  warningCount: number;
  dangerCount: number;
}

export interface TaskProgressResponse {
  asOfDate: string;
  overall: TaskProgressSummary;
  byAssignee: { assigneeId: number | null; summary: TaskProgressSummary }[];
}

const filterParams = (filters: TaskFilters) => {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value));
  });
  return params;
};

export const getTasks = (
  workspaceId: string | number,
  page = 0,
  size = 20,
  filters: TaskFilters = {},
) => {
  const params = filterParams(filters);
  params.set('page', String(page));
  params.set('size', String(size));
  return http.get<PageResponse<TaskSummary>>(ws(workspaceId, `/tasks?${params}`));
};

export const getTaskProgress = (workspaceId: string | number, filters: TaskFilters = {}) =>
  http.get<TaskProgressResponse>(ws(workspaceId, `/tasks/progress?${filterParams(filters)}`));

export const getTask = (
  workspaceId: string | number,
  taskId: string | number,
) =>
  http.get<TaskDetail>(ws(workspaceId, `/tasks/${taskId}`));

export const createTask = (
  workspaceId: string | number,
  body: TaskCreateRequest,
) =>
  http.post<TaskSummary>(ws(workspaceId, '/tasks'), body);

export const updateTask = (
  workspaceId: string | number,
  taskId: string | number,
  body: TaskUpdateRequest,
) =>
  http.patch<TaskSummary>(ws(workspaceId, `/tasks/${taskId}`), body);

export const deleteTask = (
  workspaceId: string | number,
  taskId: string | number,
) =>
  http.delete<void>(ws(workspaceId, `/tasks/${taskId}`));

export const getTaskDeletionBlockers = (workspaceId: string, taskId: number) =>
  http.get<TaskContext['meetings']>(ws(workspaceId, `/tasks/${taskId}/deletion-blockers`));

const taskPath = (workspaceId: string, taskId: string) => ws(workspaceId, `/tasks/${taskId}`);

export const getTaskContext = (workspaceId: string, taskId: string) =>
  http.get<TaskContext>(`${taskPath(workspaceId, taskId)}/context`);

export const getTasksByContent = (workspaceId: string, contentRefId: number) =>
  http.get<TaskSummary[]>(ws(workspaceId, `/contents/${contentRefId}/tasks`));

/** 여러 콘텐츠(메시지)에 연결된 팀 작업 수. 연결이 없는 콘텐츠는 빠진다. */
export const getTaskCountsByContents = (workspaceId: string, contentRefIds: number[]) =>
  contentRefIds.length === 0
    ? Promise.resolve({} as Record<string, number>)
    : http.get<Record<string, number>>(
      ws(workspaceId, `/contents/task-counts?contentRefIds=${contentRefIds.join(',')}`),
    );

export const getReferenceCandidates = (
  workspaceId: string, taskId: string, provider: TaskReferenceProvider, query: string,
) => {
  const params = new URLSearchParams({ provider, query });
  return http.get<TaskReferenceCandidate[]>(`${taskPath(workspaceId, taskId)}/references/candidates?${params}`);
};

export const linkTaskReference = (workspaceId: string, taskId: string, contentRefId: number) =>
  api<void>(`${taskPath(workspaceId, taskId)}/references/${contentRefId}`, { method: 'PUT' });

export const unlinkTaskReference = (workspaceId: string, taskId: string, contentRefId: number) =>
  http.delete<void>(`${taskPath(workspaceId, taskId)}/references/${contentRefId}`);

export const addTaskDeliverable = (workspaceId: string, taskId: string, body: DeliverableInput) =>
  http.post<TaskDeliverable>(`${taskPath(workspaceId, taskId)}/deliverables`, body);

export const removeTaskDeliverable = (workspaceId: string, taskId: string, deliverableId: number) =>
  http.delete<void>(`${taskPath(workspaceId, taskId)}/deliverables/${deliverableId}`);

export const submitTaskDeliverable = (
  workspaceId: string, taskId: string, deliverableId: number, contentRefId: number,
) => api<void>(`${taskPath(workspaceId, taskId)}/deliverables/${deliverableId}/submission`, {
  method: 'PUT',
  body: JSON.stringify({ contentRefId }),
});

export const unsubmitTaskDeliverable = (workspaceId: string, taskId: string, deliverableId: number) =>
  http.delete<void>(`${taskPath(workspaceId, taskId)}/deliverables/${deliverableId}/submission`);
