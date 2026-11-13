import { useSyncExternalStore } from 'react';

export type Theme = 'light' | 'dark';
/** indigo: 파랑·보라(기본), carrot: 당근 오렌지 */
export type Palette = 'indigo' | 'carrot';

const STORAGE_KEY = 'togather-theme';
const PALETTE_KEY = 'togather-palette';

/** 저장된 테마. 처음이면 운영체제 설정을 따른다. (index.html에서 첫 화면 전에 같은 규칙으로 적용) */
function initialTheme(): Theme {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'light' || saved === 'dark') return saved;
  } catch {
    // 저장소를 쓸 수 없으면 운영체제 설정을 따른다.
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function initialPalette(): Palette {
  try {
    if (localStorage.getItem(PALETTE_KEY) === 'carrot') return 'carrot';
  } catch {
    // 저장소를 쓸 수 없으면 기본 색을 쓴다.
  }
  return 'indigo';
}

/**
 * 테마는 화면 전체가 같은 값을 봐야 하므로(버튼, 사이드바 로고, 로그인 화면) 모듈 하나에 두고 함께 구독한다.
 * 컴포넌트마다 useState로 들고 있으면 버튼을 눌러도 다른 곳의 로고가 새로고침 전까지 바뀌지 않는다.
 */
let state = { theme: initialTheme(), palette: initialPalette() };
const listeners = new Set<() => void>();

/** <html>에 dark 클래스와 data-palette를 반영하고 저장한다. */
function apply(next: typeof state) {
  state = next;
  const root = document.documentElement;
  root.classList.toggle('dark', state.theme === 'dark');
  root.setAttribute('data-palette', state.palette);
  try {
    localStorage.setItem(STORAGE_KEY, state.theme);
    localStorage.setItem(PALETTE_KEY, state.palette);
  } catch {
    // 저장하지 못해도 이번 화면에서는 적용된다.
  }
  listeners.forEach((listener) => listener());
}

apply(state);

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

const toggle = () => apply({ ...state, theme: state.theme === 'dark' ? 'light' : 'dark' });
const setPalette = (palette: Palette) => apply({ ...state, palette });
const togglePalette = () => setPalette(state.palette === 'indigo' ? 'carrot' : 'indigo');

export function useTheme() {
  const { theme, palette } = useSyncExternalStore(subscribe, () => state);
  return { theme, toggle, palette, setPalette, togglePalette };
}
