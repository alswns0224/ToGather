// 화면에서 함께 쓰는 Tailwind 클래스 (docs/ui-prototype 시안의 cardCls, subCardCls 등)
// 라이트·다크 모드 클래스를 함께 적어 두었으므로 그대로 className에 넣으면 된다.

/** 큰 카드 (반투명 흰 배경 + 옅은 그림자) */
export const CARD =
  'rounded-[28px] border border-slate-200/80 bg-white/90 text-slate-900 shadow-[0_4px_24px_-6px_rgba(15,23,42,0.06)] backdrop-blur-xl dark:border-white/10 dark:bg-[#12141D]/90 dark:text-slate-100 dark:shadow-xl';

/** 카드 안의 작은 상자 */
export const SUB_CARD =
  'rounded-2xl border border-slate-200/70 bg-slate-50/90 text-slate-800 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200';

/** 보조 글자색 */
export const MUTED = 'text-slate-500 dark:text-slate-400';

/** 카드 머리의 작은 영문 라벨 (예: OAUTH 2.0 INTEGRATIONS) */
export const EYEBROW = 'rounded-full bg-indigo-500/15 px-3 py-1 text-[11px] font-bold text-indigo-500';

const BTN = 'inline-flex cursor-pointer items-center justify-center gap-1.5 rounded-xl text-xs font-bold no-underline transition disabled:cursor-not-allowed disabled:opacity-50';

/** 보라 그라데이션 강조 버튼 (AI 기능, 주요 동작) */
export const BTN_AI = `${BTN} border-0 bg-[linear-gradient(135deg,#4F46E5_0%,#7C3AED_55%,#C026D3_100%)] px-4 py-2.5 text-white shadow-[0_6px_20px_-4px_rgba(124,58,237,0.45)] hover:-translate-y-px hover:brightness-105`;

/** 인디고 기본 버튼 */
export const BTN_PRIMARY = `${BTN} border-0 bg-indigo-600 px-3.5 py-2 text-white hover:bg-indigo-500`;

/** 흰 보조 버튼 */
export const BTN_SUB = `${BTN} border border-slate-200/70 bg-slate-50/90 px-3.5 py-2 text-slate-700 hover:border-indigo-400 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200`;

/** 빨간 글자 보조 버튼 (해제, 삭제) */
export const BTN_DANGER = `${BTN} border border-slate-200/70 bg-slate-50/90 px-3 py-2 text-rose-500 hover:border-rose-400 dark:border-white/10 dark:bg-white/[0.04]`;

/** 입력칸 */
export const INPUT =
  'w-full rounded-2xl border border-slate-200/70 bg-slate-50/90 px-4 py-2.5 text-sm text-slate-900 outline-none focus:border-indigo-500 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-100';
