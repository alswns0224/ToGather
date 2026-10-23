import { useTheme } from '../hooks/useTheme';

/** 라이트·다크 모드 전환 버튼 */
export default function ThemeToggle() {
  const { theme, toggle } = useTheme();
  const dark = theme === 'dark';
  return (
    <button
      type="button"
      onClick={toggle}
      title={dark ? '라이트 모드로 전환' : '다크 모드로 전환'}
      aria-label={dark ? '라이트 모드로 전환' : '다크 모드로 전환'}
      className="flex h-7 w-7 cursor-pointer items-center justify-center rounded-lg border border-slate-200 bg-white text-xs text-slate-600 transition-transform hover:scale-105 dark:border-white/10 dark:bg-white/10 dark:text-slate-200"
    >
      {dark ? '☀️' : '🌙'}
    </button>
  );
}
