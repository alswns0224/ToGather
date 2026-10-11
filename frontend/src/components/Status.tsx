import type { ReactNode } from 'react';
import Icon, { type IconName } from './Icon';

/** 모든 화면에서 쓰는 세 가지 상태: 불러오는 중, 실패(재시도), 데이터 없음 (README 프론트엔드 규칙) */

export function Loading({ text = '불러오는 중...', rows = 3 }: { text?: string; rows?: number }) {
  return (
    <div className="loading-list" role="status" aria-label={text}>
      {Array.from({ length: rows }, (_, i) => (
        <div className="skeleton-row" key={i}>
          <i />
          <i />
          <i />
          <i />
        </div>
      ))}
    </div>
  );
}

export function ErrorBox({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  return (
    <div className="notice error" role="alert">
      <Icon name="alert" size={17} />
      <span>{error.message || '문제가 발생했습니다.'}</span>
      {onRetry && (
        <button type="button" className="ui-button secondary" onClick={onRetry}>
          <Icon name="retry" size={15} />
          다시 시도
        </button>
      )}
    </div>
  );
}

export function Empty({ children, icon = 'search' }: { children: ReactNode; icon?: IconName }) {
  return (
    <div className="state-view compact">
      <div className="state-illustration">
        <Icon name={icon} size={26} />
      </div>
      <span>{children}</span>
    </div>
  );
}
