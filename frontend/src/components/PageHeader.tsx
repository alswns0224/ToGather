import type { ReactNode } from 'react';
import { EYEBROW, MUTED } from './ui';

/** 화면 머리: 작은 영문 라벨 + 제목 + 설명 + 오른쪽 버튼 (docs/ui-prototype 시안) */
export default function PageHeader({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow?: string;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
      <div className="min-w-0">
        {eyebrow && <span className={EYEBROW}>{eyebrow}</span>}
        <h1 className={`${eyebrow ? 'mt-2' : ''} !text-2xl`}>{title}</h1>
        {description && <p className={`m-0 mt-1 text-xs ${MUTED}`}>{description}</p>}
      </div>
      {actions && <div className="flex flex-shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
