const pad = (n: number) => String(n).padStart(2, '0');

/** Date → 백엔드 LocalDateTime 문자열 (서울 시간, 예: 2026-10-07T19:30:00) */
export function toLocalIso(date: Date): string {
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
  );
}

/** 백엔드 LocalDateTime 문자열 → Date */
export function fromLocalIso(value: string): Date {
  return new Date(value.length === 16 ? `${value}:00` : value);
}

/** <input type="datetime-local"> 값 (예: 2026-10-07T19:30) */
export function toInputValue(date: Date): string {
  return toLocalIso(date).slice(0, 16);
}

export function formatDate(value: string | Date): string {
  const d = typeof value === 'string' ? fromLocalIso(value) : value;
  const days = ['일', '월', '화', '수', '목', '금', '토'];
  return `${d.getMonth() + 1}/${d.getDate()}(${days[d.getDay()]})`;
}

export function formatTime(value: string | Date): string {
  const d = typeof value === 'string' ? fromLocalIso(value) : value;
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function formatDateTime(value: string | Date): string {
  return `${formatDate(value)} ${formatTime(value)}`;
}

export function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

export function addDays(date: Date, days: number): Date {
  const d = new Date(date);
  d.setDate(d.getDate() + days);
  return d;
}

export function startOfDay(date: Date): Date {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  return d;
}

/** 백엔드 LocalTime("18:00" 또는 "18:00:00") → "18:00". 없으면 '' */
export function timeInput(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : '';
}

/** 마감 표시: "11/30(일) 18:00". 시각이 없으면 날짜만 */
export function formatDue(date: string, time?: string | null): string {
  return time ? `${formatDate(date)} ${timeInput(time)}` : formatDate(date);
}
