// 메뉴 아이콘: 팀원 UI 시안(togather_ui.html)의 선 아이콘 그대로
import type { ReactNode } from 'react';

const S = { fill: 'none', stroke: 'currentColor', strokeWidth: 2 } as const;
const S18 = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinejoin: 'round' } as const;

const ICONS = {
  home: (
    <g {...S}>
      <path d="M3 9.5L12 3l9 6.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />
    </g>
  ),
  search: (
    <g {...S}>
      <circle cx="11" cy="11" r="8" />
      <path d="m21 21-4.3-4.3" />
    </g>
  ),
  slack: (
    <g {...S}>
      <path d="M14.5 10c-.83 0-1.5-.67-1.5-1.5v-5c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5v5c0 .83-.67 1.5-1.5 1.5z" />
      <path d="M20.5 10H19V8.5c0-.83.67-1.5 1.5-1.5s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z" />
      <path d="M9.5 14c.83 0 1.5.67 1.5 1.5v5c0 .83-.67 1.5-1.5 1.5S8 21.33 8 20.5v-5c0-.83.67-1.5 1.5-1.5z" />
      <path d="M3.5 14H5v1.5c0 .83-.67 1.5-1.5 1.5S2 16.33 2 15.5 2.67 14 3.5 14z" />
      <path d="M14 14.5c0-.83.67-1.5 1.5-1.5h5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5h-5c-.83 0-1.5-.67-1.5-1.5z" />
      <path d="M15.5 19H14v1.5c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5-.67-1.5-1.5-1.5z" />
      <path d="M10 9.5C10 8.67 9.33 8 8.5 8h-5C2.67 8 2 8.67 2 9.5S2.67 11 3.5 11h5c.83 0 1.5-.67 1.5-1.5z" />
      <path d="M8.5 5H10V3.5C10 2.67 9.33 2 8.5 2S7 2.67 7 3.5 7.67 5 8.5 5z" />
    </g>
  ),
  analyses: (
    <g {...S}>
      <path d="M12 3l1.9 5.8H20l-4.9 3.6 1.9 5.8L12 14.6 7 18.2l1.9-5.8L4 8.8h6.1z" />
    </g>
  ),
  calendar: (
    <g {...S18}>
      <path d="M5 3.5h14a1.5 1.5 0 0 1 1.5 1.5v11.5L16.5 20.5H5A1.5 1.5 0 0 1 3.5 19V5A1.5 1.5 0 0 1 5 3.5z" />
      <path d="M16.5 20.5v-4h4" />
      <path d="M3.5 8h17" />
      <text x="12" y="16.8" textAnchor="middle" fill="currentColor" stroke="none" fontSize="8.5" fontWeight="800" fontFamily="sans-serif">
        31
      </text>
    </g>
  ),
  meetings: (
    <g {...S}>
      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" />
    </g>
  ),
  tasks: (
    <g {...S}>
      <path d="M9 11l3 3L22 4" />
      <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
    </g>
  ),
  drive: (
    <g {...S18}>
      <path d="M9.5 3.5h5L21 15l-2.5 4.5h-13L3 15 9.5 3.5z" />
      <path d="M9.5 3.5L16 15H3" />
      <path d="M14.5 3.5L8 15l2.5 4.5" />
    </g>
  ),
  notion: (
    <g {...S18} strokeLinecap="round">
      <path d="M4.5 5.5L16.5 4l3 2.2v12.8l-12 1.2-3-2.2V5.5z" />
      <path d="M4.5 5.5l3 2.2 12-1.5" />
      <path d="M7.5 7.7v12.5" />
      <path d="M10.5 16.5v-6l4.8 5.5v-6" />
    </g>
  ),
  assistant: (
    <g {...S}>
      <path d="M12 8V4H8" />
      <rect width="16" height="12" x="4" y="8" rx="2" />
      <path d="M2 14h2M20 14h2M15 13v2M9 13v2" />
    </g>
  ),
  members: (
    <g {...S}>
      <circle cx="12" cy="8" r="4" />
      <path d="M20 21a8 8 0 0 0-16 0" />
    </g>
  ),
  integrations: (
    <g {...S}>
      <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
      <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
    </g>
  ),
  workspaces: (
    <g {...S}>
      <rect x="3" y="3" width="7" height="7" rx="1" />
      <rect x="14" y="3" width="7" height="7" rx="1" />
      <rect x="3" y="14" width="7" height="7" rx="1" />
      <rect x="14" y="14" width="7" height="7" rx="1" />
    </g>
  ),
  logout: (
    <g {...S}>
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <path d="m16 17 5-5-5-5M21 12H9" />
    </g>
  ),
} satisfies Record<string, ReactNode>;

export type IconName = keyof typeof ICONS;

export default function NavIcon({ name, className = 'h-4 w-4' }: { name: IconName; className?: string }) {
  return (
    <svg className={`${className} flex-shrink-0`} viewBox="0 0 24 24" aria-hidden="true">
      {ICONS[name]}
    </svg>
  );
}
