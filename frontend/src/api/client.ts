// 백엔드 응답 형식 (README 3. API 규칙)
export interface PageData<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
  ) {
    super(message);
  }
}

type SuccessBody<T> = { data: T };
type ErrorBody = { error: { code: string; message: string } };

/** 성공 시 data만 반환하고, 실패 시 ApiError를 던진다. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`/api${path}`, {
    credentials: 'include',
    ...init,
    headers: {
      ...(init.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...init.headers,
    },
  });

  const body = res.status === 204 ? null : ((await res.json().catch(() => null)) as SuccessBody<T> | ErrorBody | null);

  if (!res.ok) {
    const err = body && 'error' in body ? body.error : { code: 'UNKNOWN', message: res.statusText };
    throw new ApiError(err.code, err.message, res.status);
  }
  return (body as SuccessBody<T> | null)?.data as T;
}

export const http = {
  get: <T>(path: string) => api<T>(path),
  post: <T>(path: string, data?: unknown) => api<T>(path, { method: 'POST', body: JSON.stringify(data ?? {}) }),
  patch: <T>(path: string, data?: unknown) => api<T>(path, { method: 'PATCH', body: JSON.stringify(data ?? {}) }),
  delete: <T>(path: string) => api<T>(path, { method: 'DELETE' }),
};

/** 워크스페이스 하위 경로: /workspaces/{workspaceId}/... */
export const ws = (workspaceId: string | number, path = '') => `/workspaces/${workspaceId}${path}`;
