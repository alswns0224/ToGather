import { createContext, useContext, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { getMe, type LoginUser } from '../pages/auth/api';
import { useAsync } from '../hooks/useAsync';
import { ApiError } from '../api/client';
import { ErrorBox, Loading } from './Status';

const UserContext = createContext<LoginUser | null>(null);

/** 로그인한 사용자 정보. RequireAuth 안에서만 쓸 수 있다. */
export function useLoginUser(): LoginUser {
  const user = useContext(UserContext);
  if (!user) throw new Error('useLoginUser는 RequireAuth 안에서만 사용할 수 있습니다.');
  return user;
}

/** 로그인하지 않았으면 로그인 화면으로 보내고, 로그인 후 원래 화면으로 돌아오게 한다. */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation();
  const { loading, error, data, reload } = useAsync(getMe, []);

  if (loading) return <Loading />;
  if (error instanceof ApiError && error.status === 401) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  if (error) return <ErrorBox error={error} onRetry={reload} />;
  return <UserContext.Provider value={data!}>{children}</UserContext.Provider>;
}
