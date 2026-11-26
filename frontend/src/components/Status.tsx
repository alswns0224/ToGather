import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../api/client';

/** 모든 화면에서 쓰는 세 가지 상태: 불러오는 중, 실패(재시도), 데이터 없음 (README 프론트엔드 규칙) */

export function Loading({ text = '불러오는 중...' }: { text?: string }) {
  return <p className="muted">{text}</p>;
}

export function ErrorBox({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  const { workspaceId } = useParams();
  // Slack·Notion·Drive는 각자 자기 계정으로 연동해야 팀 자료가 보인다.
  if (error instanceof ApiError && error.code === 'OWN_CONNECTION_REQUIRED') {
    return (
      <div className="alert">
        <span>{error.message}</span>
        {workspaceId && (
          <Link to={`/workspaces/${workspaceId}/integrations`} className="btn btn-small btn-primary">
            연동 관리로 이동
          </Link>
        )}
      </div>
    );
  }
  if (error instanceof ApiError && error.code === 'CONTENT_ACCESS_DENIED') {
    return <div className="alert">{error.message}</div>;
  }
  // 없는 항목(404)·권한 없음(403)은 다시 시도해도 같으므로, 갈 곳을 알려 준다.
  if (error instanceof ApiError && (error.status === 404 || error.status === 403)) {
    const toList = error.status === 403 || !workspaceId;
    return (
      <div className="alert alert-error">
        <span>{error.message || '찾을 수 없습니다.'}</span>
        <Link
          to={toList ? '/workspaces' : `/workspaces/${workspaceId}/home`}
          className="btn btn-small"
        >
          {toList ? '워크스페이스 목록으로' : '대시보드로 가기'}
        </Link>
      </div>
    );
  }
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
