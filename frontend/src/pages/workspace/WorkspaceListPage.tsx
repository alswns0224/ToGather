// UI-02 워크스페이스 목록 (담당: A)
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { createWorkspace, getWorkspaces } from './api';
import { logout } from '../auth/api';
import { useAsync } from '../../hooks/useAsync';
import { useLoginUser } from '../../components/RequireAuth';
import { Empty, ErrorBox, Loading } from '../../components/Status';

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
    <div className="page">
      <header className="topbar">
        <span className="logo">ToGather</span>
        <span className="spacer" />
        <span className="muted">{user.name}</span>
        <button className="btn btn-small" onClick={signOut}>
          로그아웃
        </button>
      </header>

      <div className="page-body">
        <div className="row">
          <h1>워크스페이스</h1>
          <span className="spacer" />
          <button className="btn btn-primary" onClick={() => setCreating(true)}>
            + 새 워크스페이스
          </button>
        </div>

        {creating && (
          <form className="card form" onSubmit={submit}>
            <h2>새 워크스페이스</h2>
            <label>
              이름 *
              <input
                className="input"
                required
                maxLength={100}
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
            </label>
            <label>
              설명
              <textarea
                className="input"
                maxLength={1000}
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </label>
            {submitError && <ErrorBox error={submitError} />}
            <div className="row">
              <button className="btn btn-primary" disabled={submitting}>
                {submitting ? '만드는 중...' : '만들기'}
              </button>
              <button type="button" className="btn" onClick={() => setCreating(false)}>
                취소
              </button>
            </div>
          </form>
        )}

        {loading && <Loading />}
        {error && <ErrorBox error={error} onRetry={reload} />}
        {data && data.length === 0 && !creating && (
          <Empty>
            참여 중인 워크스페이스가 없습니다. 새로 만들거나 팀장에게 초대 링크를 받으세요.
          </Empty>
        )}
        {data && data.length > 0 && (
          <div className="card-grid">
            {data.map((w) => (
              <Link key={w.workspaceId} to={`/workspaces/${w.workspaceId}`} className="card card-link">
                <div className="row">
                  <strong>{w.name}</strong>
                  <span className="spacer" />
                  {w.myRole === 'OWNER' && <span className="badge">팀장</span>}
                </div>
                {w.description && <p className="muted small clamp">{w.description}</p>}
                <p className="small">팀원 {w.memberCount}명</p>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
