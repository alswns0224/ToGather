export type AnalysisIconName =
  | 'list'
  | 'quote'
  | 'more'
  | 'close'
  | 'chevron'
  | 'check'
  | 'plus'
  | 'arrow'
  | 'document'
  | 'task'
  | 'question'
  | 'calendar';

export default function AnalysisIcon({ name, className = 'h-4 w-4' }: { name: AnalysisIconName; className?: string }) {
  return (
    <svg
      className={`${className} shrink-0`}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {name === 'list' && (
        <>
          <rect x="3" y="4" width="18" height="16" rx="3" />
          <path d="M9 4v16M13 9h4M13 14h4" />
        </>
      )}
      {name === 'quote' && (
        <>
          <path d="M9 7H4v6h5v-2c0 4-2 6-4 6M20 7h-5v6h5v-2c0 4-2 6-4 6" />
        </>
      )}
      {name === 'more' && (
        <>
          <circle cx="5" cy="12" r="1" />
          <circle cx="12" cy="12" r="1" />
          <circle cx="19" cy="12" r="1" />
        </>
      )}
      {name === 'close' && <path d="m6 6 12 12M6 18 18 6" />}
      {name === 'chevron' && <path d="m9 5 7 7-7 7" />}
      {name === 'check' && <path d="m5 12 4.5 4.5L19 7" />}
      {name === 'plus' && <path d="M12 5v14M5 12h14" />}
      {name === 'arrow' && <path d="M4 12h16m-6-6 6 6-6 6" />}
      {name === 'document' && (
        <>
          <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z" />
          <path d="M14 3v6h6M8 13h8M8 17h5" />
        </>
      )}
      {name === 'task' && (
        <>
          <rect x="4" y="4" width="16" height="16" rx="4" />
          <path d="m8 12 3 3 5-6" />
        </>
      )}
      {name === 'question' && (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 2-2.5 2-2.5 4M12 16.5h.01" />
        </>
      )}
      {name === 'calendar' && (
        <>
          <rect x="3" y="5" width="18" height="16" rx="3" />
          <path d="M7 3v4M17 3v4M3 10h18M8 14h2M14 14h2M8 17h2" />
        </>
      )}
    </svg>
  );
}
