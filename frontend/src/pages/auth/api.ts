import { http } from '../../api/client';

export interface LoginUser {
  userId: number;
  name: string;
  email: string;
  profileImage: string | null;
}

export interface AuthConfig {
  googleEnabled: boolean;
  devLoginEnabled: boolean;
}

export const getMe = () => http.get<LoginUser>('/auth/me');
export const getAuthConfig = () => http.get<AuthConfig>('/auth/config');
export const logout = () => http.post<void>('/auth/logout');
export const devLogin = (email: string, name: string) => http.post<LoginUser>('/auth/dev-login', { email, name });

/** Google 로그인은 페이지 이동으로 진행한다. redirect: 로그인 후 돌아올 앱 경로 */
export const googleLoginUrl = (redirect?: string) =>
  `/api/auth/google/login${redirect ? `?redirect=${encodeURIComponent(redirect)}` : ''}`;
