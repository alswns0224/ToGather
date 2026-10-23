import { CARD, MUTED } from './ui';

/** 아직 구현하지 않은 화면의 안내 카드 */
export default function ComingSoon({ children }: { children: string }) {
  return (
    <div className={`${CARD} p-10 text-center`}>
      <p className="m-0 text-2xl">🚧</p>
      <p className="m-0 mt-2 text-sm font-bold">준비 중인 화면입니다</p>
      <p className={`m-0 mt-1 text-xs ${MUTED}`}>{children}</p>
    </div>
  );
}
