import { http, ws } from '../../api/client';

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
