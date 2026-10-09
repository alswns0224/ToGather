import type { ReactNode } from 'react';

/** 모든 화면에서 쓰는 세 가지 상태: 불러오는 중, 실패(재시도), 데이터 없음 (README 프론트엔드 규칙) */

export function Loading({ text = '불러오는 중...' }: { text?: string }) {
  return <p className="muted">{text}</p>;
}

export function ErrorBox({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  return (
    <div className="alert alert-error">
      <span>{error.message || '문제가 발생했습니다.'}</span>
      {onRetry && (
        <button className="btn btn-small" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}
