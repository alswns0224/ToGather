import { http, ws } from '../../api/client';

export interface CalendarEvent {
  contentRefId: number;
  title: string | null;
  startAt: string;
  endAt: string;
  allDay: boolean;
  description: string | null;
  attendees: string[];
  deepLink: string | null;
  meetLink: string | null;
  meetingId: number | null;
  personal: boolean;
}

export interface CalendarEventForm {
  title: string;
  startAt: string;
  endAt: string;
  participantIds: number[];
  description: string;
}

export const getEvents = (workspaceId: string, from: string, to: string) =>
  http.get<CalendarEvent[]>(
    ws(workspaceId, `/calendar/events?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  );

export const createEvent = (workspaceId: string, body: CalendarEventForm) =>
  http.post<CalendarEvent>(ws(workspaceId, '/calendar/events'), body);

export const updateEvent = (workspaceId: string, id: number, body: Partial<CalendarEventForm>) =>
  http.patch<CalendarEvent>(ws(workspaceId, `/calendar/events/${id}`), body);

export const deleteEvent = (workspaceId: string, id: number) =>
  http.delete<void>(ws(workspaceId, `/calendar/events/${id}`));
