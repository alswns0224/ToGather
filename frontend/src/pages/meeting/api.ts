import { http, ws } from '../../api/client';

export type MeetingStatus = 'SCHEDULING' | 'CONFIRMED' | 'CANCELED';
export type SlotPreference = 'AVAILABLE' | 'UNAVAILABLE' | 'NOT_PREFERRED';

export interface TaskSummary {
  taskId: number;
  workspaceId: number;
  title: string;
  assigneeId: number | null;
  dueDate: string | null;
  status: 'IN_PROGRESS' | 'DONE';
  progress: number;
  riskLevel: 'NORMAL' | 'WARNING' | 'DANGER';
}

export interface MeetingSummaryResponse {
  meetingId: number;
  title: string;
  status: MeetingStatus;
  durationMinutes: number;
  candidateStartAt: string;
  candidateEndAt: string;
  startAt: string | null;
  meetingMode: string | null;
  participantCount: number;
  respondedCount: number;
  participating: boolean;
}

export interface ParticipantResponse {
  userId: number;
  name: string;
  profileImage: string | null;
  hasResponded: boolean;
}

export interface AgendaResponse {
  agendaId: number;
  orderNo: number;
  taskId: number;
  task: TaskSummary | null;
}

export interface MeetingDetailResponse {
  meetingId: number;
  title: string;
  status: MeetingStatus;
  durationMinutes: number;
  candidateStartAt: string;
  candidateEndAt: string;
  meetingMode: string | null;
  startAt: string | null;
  endAt: string | null;
  calendarRefId: number | null;
  meetLink: string | null;
  calendarSyncFailed: boolean;
  createdBy: number | null;
  canManage: boolean;
  participating: boolean;
  participants: ParticipantResponse[];
  agendas: AgendaResponse[];
}

export interface MeetingCreateRequest {
  title: string;
  durationMinutes: number;
  candidateStartAt: string;
  candidateEndAt: string;
  participantIds: number[];
  meetingMode?: string;
  agendaTaskIds: number[];
}

export interface AvailabilityResponse {
  candidateStartAt: string;
  candidateEndAt: string;
  slotMinutes: number;
  dayStartHour: number;
  dayEndHour: number;
  hasResponded: boolean;
  mySlots: { startAt: string; preference: SlotPreference; source: 'MANUAL' | 'CALENDAR_AUTO' }[];
  busySlots: string[];
  calendarConnected: boolean;
}

export interface ResponseStatus {
  responded: ParticipantResponse[];
  notResponded: ParticipantResponse[];
}

export interface TimeCandidate {
  candidateId: number;
  rank: number;
  startAt: string;
  endAt: string;
  availableCount: number;
  nonPreferredCount: number;
  respondedCount: number;
  participantCount: number;
  unavailableNames: string[];
}

const base = (workspaceId: string, path = '') => ws(workspaceId, `/meetings${path}`);

export const getMeetings = (w: string) => http.get<MeetingSummaryResponse[]>(base(w));
export const createMeeting = (w: string, body: MeetingCreateRequest) => http.post<MeetingDetailResponse>(base(w), body);
export const getMeeting = (w: string, id: string | number) => http.get<MeetingDetailResponse>(base(w, `/${id}`));
export const updateMeeting = (w: string, id: number, body: Partial<MeetingCreateRequest>) =>
  http.patch<MeetingDetailResponse>(base(w, `/${id}`), body);
export const cancelMeeting = (w: string, id: number) => http.post<void>(base(w, `/${id}/cancel`));

export const getAgendaCandidates = (w: string) => http.get<TaskSummary[]>(base(w, '/agenda-candidates'));
export const addAgenda = (w: string, id: number, taskId: number) =>
  http.post<MeetingDetailResponse>(base(w, `/${id}/agendas`), { taskId });
export const deleteAgenda = (w: string, id: number, agendaId: number) =>
  http.delete<MeetingDetailResponse>(base(w, `/${id}/agendas/${agendaId}`));

export const getMyAvailability = (w: string, id: number) =>
  http.get<AvailabilityResponse>(base(w, `/${id}/availability/me`));
export const saveAvailability = (w: string, id: number, slots: { startAt: string; preference: SlotPreference }[]) =>
  http.post<AvailabilityResponse>(base(w, `/${id}/availability/me`), { slots });
export const getResponseStatus = (w: string, id: number) => http.get<ResponseStatus>(base(w, `/${id}/responses`));

export const recommendTimes = (w: string, id: number) => http.post<TimeCandidate[]>(base(w, `/${id}/recommendations`));
export const confirmMeeting = (w: string, id: number, startAt: string) =>
  http.post<MeetingDetailResponse>(base(w, `/${id}/confirm`), { startAt });
export const retryCalendar = (w: string, id: number) =>
  http.post<MeetingDetailResponse>(base(w, `/${id}/calendar-retry`));
