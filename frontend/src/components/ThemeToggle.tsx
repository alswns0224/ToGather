import { useTheme } from '../hooks/useTheme';

const SQUARE =
  'flex h-9 w-9 cursor-pointer items-center justify-center rounded-[10px] border border-[var(--border)] bg-[var(--surface-2)] p-0 text-[15px] transition hover:border-[var(--primary)]';

/** 파랑·보라 ↔ 당근 오렌지 테마 + 라이트·다크 모드 (시안 상단바의 두 정사각 버튼) */
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
        className={SQUARE}
      >
        <span
          className="inline-block h-4 w-4 rounded-full shadow-[0_0_0_2px_var(--surface)]"
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
        className={SQUARE}
      >
        {dark ? '☀️' : '🌙'}
      </button>
    </div>
  );
}
