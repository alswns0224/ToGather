import { useTheme } from '../hooks/useTheme';

/** 파랑·보라(원본) ↔ 당근 오렌지 테마 + 라이트·다크 모드 아이콘 토글 */
export default function ThemeToggle() {
  const { theme, toggle, palette, togglePalette } = useTheme();
  const dark = theme === 'dark';
  const isCarrot = palette === 'carrot';
  return (
    <div className="flex items-center gap-1.5">
      <button
        type="button"
        onClick={togglePalette}
        title={isCarrot ? '파랑·보라 테마로 전환' : '당근 오렌지 테마로 전환'}
        aria-label={isCarrot ? '파랑·보라 테마로 전환' : '당근 오렌지 테마로 전환'}
        className="flex h-7 w-7 cursor-pointer items-center justify-center rounded-lg border border-slate-200 bg-white transition-transform hover:scale-105 dark:border-white/10 dark:bg-white/10"
      >
        <span
          className="h-3.5 w-3.5 rounded-full"
          style={{
            background: isCarrot
              ? 'linear-gradient(135deg, #2563eb 0%, #7c3aed 100%)'
              : 'linear-gradient(135deg, #ff6f0f 0%, #ff9a44 100%)',
          }}
        />
      </button>
      <button
        type="button"
        onClick={toggle}
        title={dark ? '라이트 모드로 전환' : '다크 모드로 전환'}
        aria-label={dark ? '라이트 모드로 전환' : '다크 모드로 전환'}
        className="flex h-7 w-7 cursor-pointer items-center justify-center rounded-lg border border-slate-200 bg-white text-xs text-slate-600 transition-transform hover:scale-105 dark:border-white/10 dark:bg-white/10 dark:text-slate-200"
      >
        {dark ? '☀️' : '🌙'}
      </button>
    </div>
  );
}
