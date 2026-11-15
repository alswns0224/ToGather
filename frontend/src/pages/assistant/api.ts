import { http, ws } from '../../api/client';

export type SuggestionType = 'SCHEDULE' | 'TASK';

export interface SuggestionDTO {
  suggestionId: number;
  type: SuggestionType;
  status: 'PENDING' | 'APPROVED' | 'DISMISSED';
  title: string;
  detectedDatetime: string | null;
  timeUndetermined: boolean;
  dueDate: string | null;
  sourceContentRefId: number | null;
  sourceProvider: 'SLACK' | 'GMAIL' | string | null;
  sourceTitle: string | null;
  sourceAuthor: string | null;
  sourceCreatedAt: string | null;
  sourceExcerpt: string | null;
  sourceLink: string | null;
}

export interface PersonalTaskResponse {
  personalTaskId: number;
  title: string;
  dueDate: string | null;
  status: 'TODO' | 'DONE';
  sourceContentRefId: number | null;
}

export interface MyTodoListDTO {
  assignedTasks: { taskId: number; workspaceId: number; title: string; dueDate: string | null }[];
  personalTasks: PersonalTaskResponse[];
  taskSuggestions: SuggestionDTO[];
}

export interface DetectResultResponse {
  scannedMessages: number;
  created: SuggestionDTO[];
}

const base = (workspaceId: string, path: string) => ws(workspaceId, `/assistant${path}`);

export const getSuggestions = (workspaceId: string, type?: SuggestionType) =>
  http.get<SuggestionDTO[]>(base(workspaceId, `/suggestions${type ? `?type=${type}` : ''}`));

export const detectCandidates = (workspaceId: string) =>
  http.post<DetectResultResponse>(base(workspaceId, '/suggestions/detect'));

export const updateSuggestion = (
  workspaceId: string,
  id: number,
  body: { title: string; detectedDatetime: string | null; dueDate: string | null },
) => http.patch<SuggestionDTO>(base(workspaceId, `/suggestions/${id}`), body);

export const approveSchedule = (workspaceId: string, id: number) =>
  http.post<{ contentRefId: number }>(base(workspaceId, `/suggestions/${id}/approve-schedule`));

export const approveTask = (workspaceId: string, id: number) =>
  http.post<{ personalTaskId: number }>(base(workspaceId, `/suggestions/${id}/approve-task`));

export const dismissSuggestion = (workspaceId: string, id: number) =>
  http.post<void>(base(workspaceId, `/suggestions/${id}/dismiss`));

export const getMyTodoList = (workspaceId: string) => http.get<MyTodoListDTO>(base(workspaceId, '/todos'));

export const completePersonalTask = (workspaceId: string, id: number) =>
  http.post<void>(base(workspaceId, `/personal-tasks/${id}/complete`));

export const createPersonalTask = (workspaceId: string, body: { title: string; dueDate: string | null }) =>
  http.post<PersonalTaskResponse>(base(workspaceId, '/personal-tasks'), body);

export const updatePersonalTask = (workspaceId: string, id: number, body: { title: string; dueDate: string | null }) =>
  http.patch<PersonalTaskResponse>(base(workspaceId, `/personal-tasks/${id}`), body);

export const reopenPersonalTask = (workspaceId: string, id: number) =>
  http.post<void>(base(workspaceId, `/personal-tasks/${id}/reopen`));

export const deletePersonalTask = (workspaceId: string, id: number) =>
  http.delete<void>(base(workspaceId, `/personal-tasks/${id}`));
