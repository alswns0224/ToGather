// UI-01 로그인 (담당: A)
import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { devLogin, getAuthConfig, googleLoginUrl } from './api';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';

const ERROR_MESSAGES: Record<string, string> = {
  access_denied: '로그인을 취소했습니다.',
  OAUTH_STATE_MISMATCH: '로그인 요청이 만료되었습니다. 다시 시도해 주세요.',
  LOGIN_FAILED: 'Google 로그인에 실패했습니다.',
  OAUTH_NOT_CONFIGURED: '서버에 Google 로그인 설정이 없습니다.',
  EXTERNAL_API_ERROR: 'Google 서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.',
};

export default function LoginPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const redirect = params.get('redirect') ?? '/workspaces';
  const errorCode = params.get('error');
  const config = useAsync(getAuthConfig, []);
  const [redirecting, setRedirecting] = useState(false);
  const [dev, setDev] = useState({ email: '', name: '' });
  const [devError, setDevError] = useState<Error>();
  const [submitting, setSubmitting] = useState(false);

  const startGoogle = () => {
    setRedirecting(true);
    window.location.href = googleLoginUrl(redirect);
  };

  const submitDev = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setDevError(undefined);
    try {
      await devLogin(dev.email, dev.name);
      navigate(redirect, { replace: true });
    } catch (err) {
      setDevError(err as Error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="center-page">
      <div className="card login-card">
        <h1 className="logo">ToGather</h1>
        <p className="muted">흩어진 대화·문서·파일·일정을 하나의 워크스페이스에서</p>

        {errorCode && (
          <div className="alert alert-error">{ERROR_MESSAGES[errorCode] ?? `로그인에 실패했습니다. (${errorCode})`}</div>
        )}

        {config.loading && <Loading />}
        {config.error && <ErrorBox error={config.error} onRetry={config.reload} />}

        {config.data && (
          <>
            <button
              className="btn btn-primary btn-block"
              onClick={startGoogle}
              disabled={!config.data.googleEnabled || redirecting}
            >
              {redirecting ? 'Google로 이동하는 중...' : 'Google 계정으로 로그인'}
            </button>
            {!config.data.googleEnabled && (
              <p className="muted small">서버에 GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET이 설정되지 않았습니다.</p>
            )}

            {config.data.devLoginEnabled && (
              <form className="dev-login" onSubmit={submitDev}>
                <p className="small muted">개발용 로그인 (DEV_LOGIN_ENABLED=true일 때만 보입니다)</p>
                <input
                  className="input"
                  type="email"
                  placeholder="이메일"
                  required
                  value={dev.email}
                  onChange={(e) => setDev({ ...dev, email: e.target.value })}
                />
                <input
                  className="input"
                  placeholder="이름"
                  required
                  value={dev.name}
                  onChange={(e) => setDev({ ...dev, name: e.target.value })}
                />
                {devError && <ErrorBox error={devError} />}
                <button className="btn btn-block" disabled={submitting}>
                  {submitting ? '로그인 중...' : '개발용 로그인'}
                </button>
              </form>
            )}
          </>
        )}
      </div>
    </div>
  );
}
