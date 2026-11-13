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

export const approveSchedule = (workspaceId: string, id: number, endAt: string | null = null) =>
  http.post<{ contentRefId: number }>(base(workspaceId, `/suggestions/${id}/approve-schedule`), { endAt });

export const approveTask = (workspaceId: string, id: number) =>
  http.post<{ personalTaskId: number }>(base(workspaceId, `/suggestions/${id}/approve-task`));

/** 일정 후보를 팀 캘린더에 등록 (참여자를 비우면 승인한 사람만) */
export const approveTeamSchedule = (workspaceId: string, id: number, participantIds: number[], endAt: string | null = null) =>
  http.post<{ contentRefId: number }>(base(workspaceId, `/suggestions/${id}/approve-team-schedule`), { participantIds, endAt });

/** 할 일 후보를 팀 Task로 승인 (담당자를 비우면 승인한 사람) */
export const approveTeamTask = (workspaceId: string, id: number, assigneeId: number | null) =>
  http.post<{ taskId: number }>(base(workspaceId, `/suggestions/${id}/approve-team-task`), { assigneeId });

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

/** 날짜만 정해진 후보(시간 00:00)는 "11/20(금) · 시간 미정"으로 보여준다. */
export const isDateOnly = (datetime: string | null) => !!datetime && /T00:00(:00)?$/.test(datetime);

export function candidateWhen(datetime: string | null, format: { date: (v: string) => string; dateTime: (v: string) => string }) {
  if (!datetime) return '시간 미정';
  return isDateOnly(datetime) ? `${format.date(datetime)} · 시간 미정` : format.dateTime(datetime);
}
