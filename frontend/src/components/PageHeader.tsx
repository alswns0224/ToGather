import type { ReactNode } from 'react';
import { MUTED } from './ui';

/**
 * 화면 머리: (아이콘) 제목 + 설명, 오른쪽에 상태·버튼 (팀원 UI 시안 togather_ui.html)
 * eyebrow는 예전 화면과의 호환을 위해 받지만 시안에 없어 그리지 않는다.
 */
export default function PageHeader({
  icon,
  title,
  description,
  actions,
}: {
  eyebrow?: string;
  icon?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="min-w-0">
        <h1 className="!m-0 flex items-center gap-[9px] !text-2xl !font-extrabold">
          {icon && <span className="inline-flex h-6 w-6 flex-shrink-0">{icon}</span>}
          <span>{title}</span>
        </h1>
        {description && <p className={`m-0 mt-1 text-[13px] ${MUTED}`}>{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2.5">{actions}</div>}
    </div>
  );
}
