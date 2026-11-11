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

export const getTasks = (
  workspaceId: string | number,
  page = 0,
  size = 20,
) =>
  http.get<PageResponse<TaskSummary>>(
    ws(workspaceId, `/tasks?page=${page}&size=${size}`),
  );

export const getMyTasks = (
  workspaceId: string | number,
  page = 0,
  size = 20,
) =>
  http.get<PageResponse<TaskSummary>>(
    ws(workspaceId, `/tasks?mine=true&page=${page}&size=${size}`),
  );

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

const taskPath = (workspaceId: string, taskId: string) => ws(workspaceId, `/tasks/${taskId}`);

export const getTaskContext = (workspaceId: string, taskId: string) =>
  http.get<TaskContext>(`${taskPath(workspaceId, taskId)}/context`);

export const getTasksByContent = (workspaceId: string, contentRefId: number) =>
  http.get<TaskSummary[]>(ws(workspaceId, `/contents/${contentRefId}/tasks`));

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

export const submitTaskDeliverable = (
  workspaceId: string, taskId: string, deliverableId: number, contentRefId: number,
) => api<void>(`${taskPath(workspaceId, taskId)}/deliverables/${deliverableId}/submission`, {
  method: 'PUT',
  body: JSON.stringify({ contentRefId }),
});

export const unsubmitTaskDeliverable = (workspaceId: string, taskId: string, deliverableId: number) =>
  http.delete<void>(`${taskPath(workspaceId, taskId)}/deliverables/${deliverableId}/submission`);
