type MeetingFormIconName = 'clock' | 'calendar' | 'video' | 'location' | 'hybrid' | 'check' | 'close' | 'arrow' | 'chevron-left' | 'chevron-right';

export default function MeetingFormIcon({ name, className = 'h-4 w-4' }: { name: MeetingFormIconName; className?: string }) {
  return <svg className={`${className} shrink-0`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {name === 'clock' && <><circle cx="12" cy="12" r="8.5" /><path d="M12 7v5l3.5 2" /></>}
    {name === 'calendar' && <><rect x="3.5" y="5" width="17" height="16" rx="3" /><path d="M7.5 3v4M16.5 3v4M3.5 10h17M8 14h3M8 17h6" /></>}
    {name === 'video' && <><rect x="3" y="6" width="12" height="12" rx="3" /><path d="m15 10 6-3v10l-6-3" /></>}
    {name === 'location' && <><path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z" /><circle cx="12" cy="10" r="2.5" /></>}
    {name === 'hybrid' && <><rect x="3" y="4" width="18" height="13" rx="3" /><path d="M8 21h8M12 17v4M8 9h8M8 12h4" /></>}
    {name === 'check' && <path d="m5 12 4.5 4.5L19 7" />}
    {name === 'close' && <path d="m6 6 12 12M6 18 18 6" />}
    {name === 'arrow' && <path d="M4 12h16m-6-6 6 6-6 6" />}
    {name === 'chevron-left' && <path d="m14 6-6 6 6 6" />}
    {name === 'chevron-right' && <path d="m10 6 6 6-6 6" />}
  </svg>;
}
