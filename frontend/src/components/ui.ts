// 화면에서 함께 쓰는 Tailwind 클래스 (팀원 UI 시안 togather_ui.html의 card, btn, input, badge)
// 색은 index.css의 테마 변수(--surface, --primary 등)를 써서 파랑·보라 / 당근 오렌지, 라이트·다크를 모두 따라간다.

/** 큰 카드 */
export const CARD =
  'rounded-[18px] border border-[var(--border)] bg-[var(--surface)] text-[var(--text)] shadow-[var(--shadow)]';

/** 카드 안의 작은 상자 */
export const SUB_CARD = 'rounded-xl border border-[var(--border-soft)] bg-[var(--surface-2)] text-[var(--text)]';

/** 보조 글자색 */
export const MUTED = 'text-[var(--muted)]';

/** 카드 머리의 작은 라벨 (예: OAUTH 2.0 INTEGRATIONS) */
export const EYEBROW = 'rounded-full bg-[var(--primary-soft)] px-3 py-1 text-[11px] font-bold text-[var(--primary)]';

const BTN =
  'inline-flex h-9 cursor-pointer items-center justify-center gap-1.5 whitespace-nowrap rounded-[10px] px-3.5 text-[13px] font-semibold no-underline transition disabled:cursor-not-allowed disabled:opacity-50';

/** 브랜드 그라데이션 강조 버튼 (AI 기능, 주요 동작) */
export const BTN_AI = `${BTN} border-0 bg-[image:var(--brand-grad)] text-white hover:brightness-95`;

/** 기본 버튼 */
export const BTN_PRIMARY = `${BTN} border-0 bg-[var(--primary)] text-white hover:bg-[var(--primary-hover)]`;

/** 보조 버튼 */
export const BTN_SUB = `${BTN} border border-[var(--border)] bg-[var(--surface-2)] text-[var(--text)] hover:border-[var(--primary)]`;

/** 빨간 글자 보조 버튼 (해제, 삭제) */
export const BTN_DANGER = `${BTN} border-0 bg-[var(--danger-soft)] text-[var(--danger)] hover:brightness-95`;

/** 입력칸 */
export const INPUT =
  'w-full rounded-[10px] border border-[var(--border)] bg-[var(--surface)] px-3 py-2 text-sm text-[var(--text)] outline-none focus:border-[var(--primary)]';
