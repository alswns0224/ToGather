// UI-01 로그인 (가로 3단계 흐름 카드 + 60:40 분할 + 파랑·보라 / 당근 오렌지 공통 지원)
import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { devLogin, getAuthConfig, googleLoginUrl } from './api';
import { useAsync } from '../../hooks/useAsync';
import { useTheme } from '../../hooks/useTheme';
import { ErrorBox, Loading } from '../../components/Status';
import ThemeToggle from '../../components/ThemeToggle';
import { BTN_SUB, INPUT, MUTED } from '../../components/ui';

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
  const { theme, palette } = useTheme();
  const [redirecting, setRedirecting] = useState(false);
  const [dev, setDev] = useState({ email: '', name: '' });
  const [devError, setDevError] = useState<Error>();
  const [submitting, setSubmitting] = useState(false);

  const isIndigo = palette === 'indigo';
  const isDark = theme === 'dark';

  const logoSrc = isIndigo
    ? '/togather-logo-mark.png'
    : isDark
      ? '/togather-logo-white.png'
      : '/togather-logo-carrot.png';

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
    <div
      style={{ backgroundColor: 'var(--bg)', backgroundImage: 'var(--aurora)' }}
      className="relative flex min-h-screen w-full items-center justify-center p-6"
    >
      <div className="fixed top-4 right-5 z-20">
        <ThemeToggle />
      </div>

      <div className="grid w-full max-w-6xl grid-cols-1 overflow-hidden rounded-3xl border border-[var(--border)] bg-[var(--surface)] shadow-xl lg:grid-cols-12">
        <section
          style={{
            background: isDark ? (isIndigo ? '#111625' : '#181411') : isIndigo ? '#f5f8ff' : '#fdfaf4',
          }}
          className="order-2 relative flex items-center justify-center overflow-hidden border-t border-[var(--border-soft)] px-6 py-10 lg:order-1 lg:col-span-7 lg:border-t-0 lg:border-r lg:px-10 lg:py-12"
        >
          <div className="relative z-10 w-full max-w-xl space-y-6">
            <div>
              <span
                className="inline-block rounded-full px-3.5 py-1 text-xs font-bold"
                style={{
                  background: isIndigo ? (isDark ? 'rgba(99,102,241,0.2)' : '#e0e7ff') : isDark ? 'rgba(132,204,22,0.18)' : '#e6edd6',
                  color: isIndigo ? (isDark ? '#c7d2fe' : '#3730a3') : isDark ? '#bef264' : '#3f5228',
                }}
              >
                팀을 위한 연결된 워크스페이스
              </span>
              <h1 className="mt-3.5 text-4xl leading-[1.18] font-extrabold tracking-tight text-[var(--text)] lg:text-5xl">
                모여서,{' '}
                <span
                  style={{
                    background: 'var(--brand-grad)',
                    WebkitBackgroundClip: 'text',
                    WebkitTextFillColor: 'transparent',
                  }}
                >
                  다 함께.
                </span>
              </h1>
              <p className="mt-3 text-[15px] leading-relaxed text-[var(--muted)]">
                대화·자료·일정을 연결하고,
                <br />
                우리 팀이 해야 할 일을 함께 정리하세요.
              </p>
            </div>

            <div className="grid grid-cols-1 items-center gap-2.5 md:grid-cols-[1fr_auto_0.9fr_auto_1.2fr]">
              <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-3.5 shadow-xs">
                <div className="mb-1.5 text-xs font-bold text-[var(--text)]">Slack</div>
                <div className="mb-2 text-[13px] font-bold text-[var(--text)]">팀 대화</div>
                <div className="rounded-lg bg-[var(--surface-2)] px-2.5 py-2 text-xs text-[var(--muted)]">
                  발표자료는 금요일까지 정리해요.
                </div>
              </div>

              <div className="text-center text-base font-bold text-[var(--muted)]">→</div>

              <div
                className="rounded-2xl border p-3.5 shadow-xs"
                style={{
                  background: isIndigo ? (isDark ? '#1c2338' : '#eef2ff') : isDark ? '#282218' : '#fef8dc',
                  borderColor: isIndigo ? (isDark ? '#334166' : '#c7d2fe') : isDark ? '#4a3d24' : '#f3e4a6',
                }}
              >
                <div className="mb-2.5 text-[13px] font-bold text-[var(--text)]">✓ 결정사항</div>
                <div className="rounded-lg bg-[var(--surface)] px-2.5 py-2 text-xs font-semibold text-[var(--text)]">
                  발표자료 최종 정리
                </div>
              </div>

              <div className="text-center text-base font-bold text-[var(--muted)]">→</div>

              <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-3.5 shadow-xs">
                <div className="mb-1.5 flex items-center justify-between">
                  <span className="text-xs font-bold text-[var(--text)]">☑ Task</span>
                  <span className="rounded-full bg-[var(--success-soft)] px-2 py-0.5 text-[11px] font-bold text-[var(--success)]">
                    진행 중
                  </span>
                </div>
                <div className="mb-2 text-[13px] font-bold text-[var(--text)]">중간 발표자료 정리</div>
                <div className="mb-2 flex items-center gap-2">
                  <div className="h-2 flex-1 overflow-hidden rounded-full bg-[var(--surface-2)]">
                    <div className="h-full w-1/2 rounded-full" style={{ background: 'var(--brand-grad)' }} />
                  </div>
                  <span className="text-[11px] font-bold text-[var(--muted)]">50%</span>
                </div>
                <div className="rounded-lg bg-[var(--surface-2)] px-2.5 py-1.5 text-xs font-semibold text-[var(--text)]">
                  📄 발표자료.pdf
                </div>
              </div>
            </div>
          </div>
        </section>

        <section className="order-1 flex items-center justify-center bg-[var(--surface)] px-6 py-10 lg:order-2 lg:col-span-5 lg:px-10">
          <div className="w-full max-w-xs space-y-4">
            <div className="flex items-center gap-2.5">
              <img src={logoSrc} alt="ToGather 로고" className="h-9 w-auto" />
              <span className="text-2xl font-extrabold tracking-tight text-[var(--text)]">ToGather</span>
            </div>

            <div>
              <h2 className="m-0 text-xl font-extrabold text-[var(--text)]">ToGather 시작하기</h2>
              <p className={`mt-1 text-sm ${MUTED}`}>Google 계정으로 간편하게 시작하세요.</p>
            </div>

            {errorCode && (
              <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-500">
                {ERROR_MESSAGES[errorCode] ?? `로그인에 실패했습니다. (${errorCode})`}
              </div>
            )}
            {config.loading && <Loading />}
            {config.error && <ErrorBox error={config.error} onRetry={config.reload} />}

            {config.data && (
              <>
                <button
                  type="button"
                  className="flex h-12 w-full cursor-pointer items-center justify-center gap-2.5 rounded-xl border border-[var(--border)] bg-[var(--surface)] px-4 text-[15px] font-bold text-[var(--text)] shadow-xs transition hover:-translate-y-0.5 hover:border-[var(--primary)] disabled:cursor-not-allowed disabled:opacity-50"
                  onClick={startGoogle}
                  disabled={!config.data.googleEnabled || redirecting}
                >
                  <span>{redirecting ? 'Google로 이동하는 중...' : 'Google 계정으로 계속하기'}</span>
                </button>
                <p className={`text-center text-xs ${MUTED}`}>
                  {config.data.googleEnabled
                    ? '별도 가입 없이 바로 시작할 수 있어요.'
                    : '서버에 GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET이 설정되지 않았습니다.'}
                </p>

                {config.data.devLoginEnabled && (
                  <details className="border-t border-dashed border-[var(--border)] pt-3">
                    <summary className={`cursor-pointer text-xs font-semibold ${MUTED}`}>개발용 로그인 (선택)</summary>
                    <form className="mt-3 flex flex-col gap-2" onSubmit={submitDev}>
                      <input
                        className={INPUT}
                        type="email"
                        placeholder="이메일"
                        required
                        value={dev.email}
                        onChange={(e) => setDev({ ...dev, email: e.target.value })}
                      />
                      <input
                        className={INPUT}
                        placeholder="이름"
                        required
                        value={dev.name}
                        onChange={(e) => setDev({ ...dev, name: e.target.value })}
                      />
                      {devError && <ErrorBox error={devError} />}
                      <button className={`${BTN_SUB} py-2.5`} disabled={submitting}>
                        {submitting ? '로그인 중...' : '개발용 로그인'}
                      </button>
                    </form>
                  </details>
                )}
              </>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
