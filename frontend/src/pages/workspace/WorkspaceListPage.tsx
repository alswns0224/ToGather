// UI-02 워크스페이스 목록 (담당: A)
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { createWorkspace, getWorkspaces } from './api';
import { logout } from '../auth/api';
import { useAsync } from '../../hooks/useAsync';
import { useLoginUser } from '../../components/RequireAuth';
import { ErrorBox, Loading } from '../../components/Status';
import ThemeToggle from '../../components/ThemeToggle';
import { BTN_AI, BTN_SUB, CARD, INPUT, MUTED, SUB_CARD } from '../../components/ui';

const WS_COLORS = ['linear-gradient(135deg,#4F46E5,#7C3AED)', 'linear-gradient(135deg,#0D9488,#2563EB)'];

export default function WorkspaceListPage() {
  const user = useLoginUser();
  const navigate = useNavigate();
  const { loading, error, data, reload } = useAsync(getWorkspaces, []);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ name: '', description: '' });
  const [submitError, setSubmitError] = useState<Error>();
  const [submitting, setSubmitting] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const created = await createWorkspace(form);
      navigate(`/workspaces/${created.workspaceId}`);
    } catch (err) {
      setSubmitError(err as Error);
    } finally {
      setSubmitting(false);
    }
  };

  const signOut = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="min-h-screen p-6 text-slate-900 lg:p-10 dark:text-white">
      <div className="mx-auto max-w-6xl space-y-8">
        <header className={`${CARD} flex flex-wrap items-center justify-between gap-4 !rounded-3xl px-6 py-4`}>
          <div className="flex items-center gap-3">
            <div className={`${BTN_AI} !h-9 !w-9 !p-0 text-base`}>T</div>
            <div>
              <span className="text-lg font-bold">ToGather</span>
              <span className={`block text-xs sm:ml-2 sm:inline ${MUTED}`}>프로젝트별 워크스페이스</span>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <span className={`text-xs font-bold ${MUTED}`}>{user.name}</span>
            <ThemeToggle />
            <button
              className="cursor-pointer border-0 bg-transparent p-0 text-xs font-bold text-rose-500 hover:underline"
              onClick={signOut}
            >
              로그아웃
            </button>
          </div>
        </header>

        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
          <div>
            <p className="m-0 mb-1 text-xs font-bold tracking-wider text-indigo-500 uppercase">MY WORKSPACES</p>
            <h1 className="!text-3xl">어디로 입장할까요?</h1>
          </div>
          <button className={`${BTN_AI} !rounded-2xl px-5`} onClick={() => setCreating(true)}>
            + 새 워크스페이스 만들기
          </button>
        </div>

        {creating && (
          <form className={`${CARD} space-y-4 p-7`} onSubmit={submit}>
            <h2>새 워크스페이스</h2>
            <label className="block">
              <span className={`mb-1 block text-xs font-bold ${MUTED}`}>이름 *</span>
              <input
                className={INPUT}
                required
                maxLength={100}
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
            </label>
            <label className="block">
              <span className={`mb-1 block text-xs font-bold ${MUTED}`}>설명</span>
              <textarea
                className={`${INPUT} min-h-20 resize-y`}
                maxLength={1000}
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </label>
            {submitError && <ErrorBox error={submitError} />}
            <div className="flex gap-2">
              <button className={BTN_AI} disabled={submitting}>
                {submitting ? '만드는 중...' : '만들기'}
              </button>
              <button type="button" className={BTN_SUB} onClick={() => setCreating(false)}>
                취소
              </button>
            </div>
          </form>
        )}

        {loading && <Loading />}
        {error && <ErrorBox error={error} onRetry={reload} />}
        {data && data.length === 0 && !creating && (
          <div className={`${CARD} p-10 text-center`}>
            <p className={`m-0 text-sm ${MUTED}`}>
              참여 중인 워크스페이스가 없습니다. 새로 만들거나 팀장에게 초대 링크를 받으세요.
            </p>
          </div>
        )}
        {data && data.length > 0 && (
          <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
            {data.map((w, idx) => (
              <Link
                key={w.workspaceId}
                to={`/workspaces/${w.workspaceId}`}
                className={`${CARD} group relative flex flex-col justify-between overflow-hidden p-7 no-underline transition-all hover:-translate-y-1 hover:border-indigo-500/60`}
              >
                <div>
                  <div className="mb-5 flex items-center justify-between gap-2">
                    <div
                      className="flex h-12 w-12 items-center justify-center rounded-2xl text-lg font-bold text-white shadow-lg"
                      style={{ background: WS_COLORS[idx % WS_COLORS.length] }}
                    >
                      {w.name.slice(0, 1)}
                    </div>
                    <span
                      className={`rounded-full px-3 py-1 text-[11px] font-bold ${
                        w.myRole === 'OWNER'
                          ? 'border border-indigo-500/30 bg-indigo-500/15 text-indigo-500'
                          : `${SUB_CARD} !rounded-full`
                      }`}
                    >
                      {w.myRole === 'OWNER' ? '👑 팀장' : '팀원'}
                    </span>
                  </div>
                  <h3 className="m-0 text-xl font-bold transition-colors group-hover:text-indigo-500">{w.name}</h3>
                  {w.description && (
                    <p className={`m-0 mt-2 line-clamp-2 text-xs leading-relaxed ${MUTED}`}>{w.description}</p>
                  )}
                </div>

                <div className="mt-7 flex items-center justify-between border-t border-slate-200/70 pt-5 dark:border-white/10">
                  <span className={`text-xs font-bold ${MUTED}`}>팀원 {w.memberCount}명</span>
                  <span className="text-xs font-bold text-indigo-500">입장하기 →</span>
                </div>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
