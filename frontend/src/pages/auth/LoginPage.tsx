// UI-01 로그인 (담당: A)
import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { devLogin, getAuthConfig, googleLoginUrl } from './api';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';
import ThemeToggle from '../../components/ThemeToggle';
import ServiceLogo from '../../components/ServiceLogo';
import { BTN_AI, BTN_SUB, CARD, INPUT, MUTED } from '../../components/ui';

const FLOW = [
  { icon: '💬 📄 📁', title: '협업 도구 수집', desc: 'Slack · Notion · Google', hi: false },
  { icon: '✨ 🧠', title: 'Gemini 논의 분석', desc: '결정 · 후속 작업 추출', hi: true },
  { icon: '✅ 📅 📄', title: '검토 후 실행', desc: 'Task · 회의 · 문서', hi: false },
];

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
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden p-6 text-slate-900 dark:text-white">
      <div className="fixed top-6 right-6 z-20">
        <ThemeToggle />
      </div>

      <div className={`${CARD} grid w-full max-w-5xl grid-cols-1 overflow-hidden !shadow-2xl lg:grid-cols-12`}>
        {/* 왼쪽 소개 */}
        <div className="relative flex flex-col justify-between overflow-hidden bg-gradient-to-br from-indigo-950 via-[#0F1026] to-purple-950 p-10 text-white lg:col-span-7 lg:p-12">
          <div>
            <div className="mb-8 inline-flex items-center gap-2.5 rounded-full border border-white/15 bg-white/10 px-3.5 py-1.5 text-xs font-semibold text-indigo-200 backdrop-blur-md">
              <span className="h-2 w-2 animate-ping rounded-full bg-emerald-400" />
              <span>팀 프로젝트 통합 워크스페이스</span>
            </div>
            <h1 className="text-4xl leading-[1.15] font-bold tracking-tight lg:text-5xl">
              모두,{' '}
              <span className="bg-gradient-to-r from-indigo-300 via-purple-300 to-pink-300 bg-clip-text text-transparent">
                다 함께.
              </span>
            </h1>
            <p className="mt-4 max-w-md text-sm leading-relaxed text-slate-300">
              Slack, Notion, Google Drive, Calendar, Gmail에 흩어진 대화 · 문서 · 파일 · 일정을 하나의 워크스페이스에서
              연결합니다.
            </p>
          </div>

          <div className="my-8 rounded-2xl border border-white/10 bg-white/[0.06] p-5 backdrop-blur-md">
            <div className="mb-3 text-xs font-bold text-indigo-200">✨ ToGather 협업 흐름</div>
            <div className="grid grid-cols-3 gap-2.5 text-center">
              {FLOW.map((f) => (
                <div
                  key={f.title}
                  className={`rounded-xl border p-3 ${f.hi ? 'border-indigo-400/40 bg-indigo-500/20' : 'border-white/10 bg-white/5'}`}
                >
                  <div className="mb-1 text-base">{f.icon}</div>
                  <p className={`m-0 text-[11px] font-bold ${f.hi ? 'text-indigo-200' : 'text-white'}`}>{f.title}</p>
                  <p className="m-0 mt-0.5 text-[10px] text-slate-400">{f.desc}</p>
                </div>
              ))}
            </div>
          </div>

          <div className="flex items-center justify-between text-xs text-slate-400">
            <span>연동 서비스</span>
            <div className="flex items-center gap-1.5">
              {(['slack', 'notion', 'drive', 'gcal', 'gmail'] as const).map((svc) => (
                <span key={svc} className="flex h-6 w-6 items-center justify-center overflow-hidden rounded-lg bg-white">
                  <ServiceLogo service={svc} />
                </span>
              ))}
            </div>
          </div>
        </div>

        {/* 오른쪽 로그인 */}
        <div className="flex flex-col justify-between p-10 lg:col-span-5 lg:p-12">
          <div>
            <div className="mb-8 flex items-center gap-3">
              <div className={`${BTN_AI} !h-11 !w-11 !rounded-2xl !p-0 text-xl`}>T</div>
              <div>
                <h2 className="m-0 text-2xl font-bold">ToGather</h2>
                <p className={`m-0 text-xs ${MUTED}`}>흩어진 대화·문서·파일·일정을 하나의 워크스페이스에서</p>
              </div>
            </div>

            <h3 className="m-0 mb-1.5 text-xl font-bold">시작하기</h3>
            <p className={`mb-6 text-xs leading-relaxed ${MUTED}`}>
              별도 가입 없이 Google 계정 하나로 워크스페이스에 접속합니다.
            </p>

            {errorCode && (
              <div className="mb-5 rounded-2xl border border-rose-500/30 bg-rose-500/10 p-4 text-rose-500">
                <p className="m-0 text-xs font-bold">⚠️ 로그인하지 못했습니다</p>
                <p className="m-0 mt-1 text-[11px] leading-relaxed">
                  {ERROR_MESSAGES[errorCode] ?? `로그인에 실패했습니다. (${errorCode})`}
                </p>
              </div>
            )}

            {config.loading && <Loading />}
            {config.error && <ErrorBox error={config.error} onRetry={config.reload} />}

            {config.data && (
              <>
                {redirecting && (
                  <div className="mb-5 rounded-2xl border border-violet-500/35 bg-gradient-to-br from-indigo-500/10 via-purple-500/10 to-pink-500/5 p-5 text-center">
                    <div className="mx-auto mb-2.5 h-8 w-8 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent" />
                    <p className="m-0 text-xs font-bold">Google로 이동하는 중...</p>
                  </div>
                )}
                <button
                  className="flex w-full cursor-pointer items-center justify-center gap-3 rounded-2xl border-0 bg-slate-900 px-5 py-4 text-sm font-bold text-white shadow-lg transition-all hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-white dark:text-slate-900 dark:hover:bg-slate-100"
                  onClick={startGoogle}
                  disabled={!config.data.googleEnabled || redirecting}
                >
                  {errorCode ? 'Google 계정으로 다시 시도' : 'Google 계정으로 계속하기'}
                </button>
                {!config.data.googleEnabled && (
                  <p className={`mt-2 text-xs ${MUTED}`}>
                    서버에 GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET이 설정되지 않았습니다.
                  </p>
                )}

                {config.data.devLoginEnabled && (
                  <form
                    className="mt-6 flex flex-col gap-2 border-0 border-t border-dashed border-slate-200 pt-6 dark:border-white/10"
                    onSubmit={submitDev}
                  >
                    <p className={`m-0 text-[11px] font-bold ${MUTED}`}>
                      개발용 로그인 (DEV_LOGIN_ENABLED=true일 때만 보입니다)
                    </p>
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
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
