// 왼쪽 메뉴 아이콘 (Heroicons + Notion/Drive/Google Calendar 심볼)
const PATHS = {
  tasks:
    'M3 4.5A1.5 1.5 0 0 1 4.5 3h11A1.5 1.5 0 0 1 17 4.5v11a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 15.5v-11Zm8.78 3.22a.75.75 0 0 0-1.06-1.06l-3.47 3.47-1.22-1.22a.75.75 0 0 0-1.06 1.06l1.75 1.75a.75.75 0 0 0 1.06 0l4-4Z',
  sparkle:
    'M15.98 1.804a1 1 0 0 0-1.96 0l-.24 1.192a1 1 0 0 1-.784.785l-1.192.238a1 1 0 0 0 0 1.962l1.192.238a1 1 0 0 1 .785.785l.238 1.192a1 1 0 0 0 1.962 0l.238-1.192a1 1 0 0 1 .785-.785l1.192-.238a1 1 0 0 0 0-1.962l-1.192-.238a1 1 0 0 1-.785-.785l-.238-1.192ZM6.949 5.684a1 1 0 0 0-1.898 0l-.683 2.051a1 1 0 0 1-.633.633l-2.051.683a1 1 0 0 0 0 1.898l2.051.684a1 1 0 0 1 .633.632l.683 2.051a1 1 0 0 0 1.898 0l.683-2.051a1 1 0 0 1 .633-.633l2.051-.683a1 1 0 0 0 0-1.898l-2.051-.683a1 1 0 0 1-.633-.633L6.95 5.684Z',
  meeting:
    'M3.25 4A2.25 2.25 0 0 0 1 6.25v7.5A2.25 2.25 0 0 0 3.25 16h7.5A2.25 2.25 0 0 0 13 13.75V6.25A2.25 2.25 0 0 0 10.75 4h-7.5ZM14.03 7.33a.75.75 0 0 0-1.03.69v4a.75.75 0 0 0 1.03.69l4-2a.75.75 0 0 0 0-1.38l-4-2Z',
  chat: 'M10 2c-2.236 0-4.43.18-6.57.524C1.993 2.755 1 4.014 1 5.426v5.148c0 1.413.993 2.67 2.43 2.902.848.137 1.705.248 2.57.331v3.443a.75.75 0 0 0 1.28.53l3.58-3.579a40.493 40.493 0 0 0 4.12-.375c1.437-.232 2.43-1.49 2.43-2.902V5.426c0-1.413-.993-2.67-2.43-2.902A41.102 41.102 0 0 0 10 2Z',
  search:
    'M9 3.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11ZM2 9a7 7 0 1 1 12.452 4.391l3.328 3.329a.75.75 0 1 1-1.06 1.06l-3.329-3.328A7 7 0 0 1 2 9Z',
  team: 'M7 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM14.5 9a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM1.615 16.428a1.224 1.224 0 0 1-.569-1.175 6.002 6.002 0 0 1 11.908 0c.058.467-.172.92-.57 1.174A9.953 9.953 0 0 1 7 17a9.953 9.953 0 0 1-5.385-1.572ZM14.5 17c-.966 0-1.87-.248-2.657-.686a3.502 3.502 0 0 0-1.04-3.53 5 5 0 0 1 8.197 2.57c.084.46-.152.909-.582 1.125A8.454 8.454 0 0 1 14.5 17Z',
  link: 'M12.232 4.232a2.5 2.5 0 0 1 3.536 3.536l-1.225 1.224a.75.75 0 0 0 1.061 1.06l1.224-1.224a4 4 0 0 0-5.656-5.656l-3 3a4 4 0 0 0 .225 5.865.75.75 0 0 0 .977-1.138 2.5 2.5 0 0 1-.142-3.667l3-3ZM11.603 7.963a.75.75 0 0 0-.977 1.138 2.5 2.5 0 0 1 .142 3.667l-3 3a2.5 2.5 0 0 1-3.536-3.536l1.225-1.224a.75.75 0 0 0-1.061-1.06l-1.224 1.224a4 4 0 1 0 5.656 5.656l3-3a4 4 0 0 0-.225-5.865Z',
  logout:
    'M3 4.25A2.25 2.25 0 0 1 5.25 2h5.5A2.25 2.25 0 0 1 13 4.25v2a.75.75 0 0 1-1.5 0v-2a.75.75 0 0 0-.75-.75h-5.5a.75.75 0 0 0-.75.75v11.5c0 .414.336.75.75.75h5.5a.75.75 0 0 0 .75-.75v-2a.75.75 0 0 1 1.5 0v2A2.25 2.25 0 0 1 10.75 18h-5.5A2.25 2.25 0 0 1 3 15.75V4.25ZM6 10a.75.75 0 0 1 .75-.75h9.546l-1.048-.943a.75.75 0 1 1 1.004-1.114l2.5 2.25a.75.75 0 0 1 0 1.114l-2.5 2.25a.75.75 0 1 1-1.004-1.114l1.048-.943H6.75A.75.75 0 0 1 6 10Z',
} as const;

export type IconName = keyof typeof PATHS | 'document' | 'folder' | 'calendar';

export default function NavIcon({ name, className = 'h-4 w-4' }: { name: IconName; className?: string }) {
  if (name === 'document') {
    return (
      <svg className={`${className} flex-shrink-0`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M4.5 5.5L16.5 4l3 2.2v12.8l-12 1.2-3-2.2V5.5z" />
        <path d="M4.5 5.5l3 2.2 12-1.5" />
        <path d="M7.5 7.7v12.5" />
        <path d="M10.5 16.5v-6l4.8 5.5v-6" />
      </svg>
    );
  }
  if (name === 'folder') {
    return (
      <svg className={`${className} flex-shrink-0`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
        <path d="M9.5 3.5h5L21 15l-2.5 4.5h-13L3 15 9.5 3.5z" />
        <path d="M9.5 3.5L16 15H3" />
        <path d="M14.5 3.5L8 15l2.5 4.5" />
      </svg>
    );
  }
  if (name === 'calendar') {
    return (
      <svg className={`${className} flex-shrink-0`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
        <path d="M5 3.5h14a1.5 1.5 0 0 1 1.5 1.5v11.5L16.5 20.5H5A1.5 1.5 0 0 1 3.5 19V5A1.5 1.5 0 0 1 5 3.5z" />
        <path d="M16.5 20.5v-4h4" />
        <path d="M3.5 8h17" />
        <text x="12" y="16.8" textAnchor="middle" fill="currentColor" stroke="none" fontSize="8.5" fontWeight="800" fontFamily="sans-serif">
          31
        </text>
      </svg>
    );
  }
  return (
    <svg className={`${className} flex-shrink-0`} viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path fillRule="evenodd" clipRule="evenodd" d={PATHS[name]} />
    </svg>
  );
}
