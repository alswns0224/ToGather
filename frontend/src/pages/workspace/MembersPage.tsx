// UI-02 팀원 관리 · 워크스페이스 설정 (담당: A)
import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import {
  createInviteLink,
  deleteWorkspace,
  getActiveInviteLink,
  getMembers,
  getWorkspace,
  removeMember,
  updateWorkspace,
  type InviteLinkResponse,
} from './api';
import { useAsync } from '../../hooks/useAsync';
import { useLoginUser } from '../../components/RequireAuth';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import { formatDateTime } from '../../utils/date';

export default function MembersPage() {
  const { workspaceId = '' } = useParams();
  const user = useLoginUser();
  const navigate = useNavigate();
  const workspace = useAsync(() => getWorkspace(workspaceId), [workspaceId]);
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  const isOwner = workspace.data?.myRole === 'OWNER';
  const [actionError, setActionError] = useState<Error>();

  const remove = async (userId: number, name: string) => {
    if (!window.confirm(`${name}님을 워크스페이스에서 내보낼까요?`)) return;
    setActionError(undefined);
    try {
      members.setData(await removeMember(workspaceId, userId));
    } catch (err) {
      setActionError(err as Error);
    }
  };

  return (
    <section className="stack">
      <h1>팀원</h1>
      {isOwner && <InviteLinkBox workspaceId={workspaceId} />}

      {actionError && <ErrorBox error={actionError} />}
      {members.loading && <Loading />}
      {members.error && <ErrorBox error={members.error} onRetry={members.reload} />}
      {members.data && members.data.length === 0 && <Empty>팀원이 없습니다.</Empty>}
      {members.data && members.data.length > 0 && (
        <ul className="list card">
          {members.data.map((m) => (
            <li key={m.userId} className="row">
              <Avatar name={m.name} src={m.profileImage} />
              <div>
                <strong>{m.name}</strong> {m.userId === user.userId && <span className="muted small">(나)</span>}
                <div className="muted small">{m.email}</div>
              </div>
              <span className="spacer" />
              <span className={`badge ${m.role === 'OWNER' ? 'badge-primary' : ''}`}>
                {m.role === 'OWNER' ? '팀장' : '팀원'}
              </span>
              {isOwner && m.role !== 'OWNER' && (
                <button className="btn btn-small btn-danger" onClick={() => remove(m.userId, m.name)}>
                  내보내기
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {isOwner && workspace.data && (
        <WorkspaceSettings
          workspaceId={workspaceId}
          initial={{ name: workspace.data.name, description: workspace.data.description ?? '' }}
          onSaved={workspace.reload}
          onDeleted={() => navigate('/workspaces', { replace: true })}
        />
      )}
    </section>
  );
}

function InviteLinkBox({ workspaceId }: { workspaceId: string }) {
  const active = useAsync(() => getActiveInviteLink(workspaceId), [workspaceId]);
  const [link, setLink] = useState<InviteLinkResponse | null>(null);
  const [error, setError] = useState<Error>();
  const [copied, setCopied] = useState(false);
  const current = link ?? active.data ?? null;

  const create = async () => {
    setError(undefined);
    try {
      setLink(await createInviteLink(workspaceId));
      setCopied(false);
    } catch (err) {
      setError(err as Error);
    }
  };

  const copy = async () => {
    if (!current) return;
    await navigator.clipboard.writeText(current.url);
    setCopied(true);
  };

  return (
    <div className="card stack">
      <strong>초대 링크</strong>
      {active.loading && <Loading />}
      {error && <ErrorBox error={error} />}
      {current ? (
        <>
          <div className="row">
            <input className="input" readOnly value={current.url} onFocus={(e) => e.target.select()} />
            <button className="btn" onClick={copy}>
              {copied ? '복사됨' : '복사'}
            </button>
          </div>
          <span className="muted small">{formatDateTime(current.expiresAt)}까지 유효합니다.</span>
          <div>
            <button className="btn btn-small" onClick={create}>
              새 링크 만들기
            </button>
          </div>
        </>
      ) : (
        !active.loading && (
          <div>
            <button className="btn btn-primary" onClick={create}>
              초대 링크 만들기
            </button>
          </div>
        )
      )}
    </div>
  );
}

function WorkspaceSettings({
  workspaceId,
  initial,
  onSaved,
  onDeleted,
}: {
  workspaceId: string;
  initial: { name: string; description: string };
  onSaved: () => void;
  onDeleted: () => void;
}) {
  const [form, setForm] = useState(initial);
  const [error, setError] = useState<Error>();
  const [saved, setSaved] = useState(false);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setError(undefined);
    try {
      await updateWorkspace(workspaceId, form);
      setSaved(true);
      onSaved();
    } catch (err) {
      setError(err as Error);
    }
  };

  const remove = async () => {
    const typed = window.prompt(
      `워크스페이스와 모든 데이터(Task, 회의, 연동)가 삭제됩니다.\n삭제하려면 워크스페이스 이름 "${initial.name}"을 입력하세요.`,
    );
    if (typed !== initial.name) return;
    try {
      await deleteWorkspace(workspaceId);
      onDeleted();
    } catch (err) {
      setError(err as Error);
    }
  };

  return (
    <form className="card form" onSubmit={save}>
      <strong>워크스페이스 설정 (팀장)</strong>
      <label>
        이름
        <input
          className="input"
          required
          maxLength={100}
          value={form.name}
          onChange={(e) => {
            setForm({ ...form, name: e.target.value });
            setSaved(false);
          }}
        />
      </label>
      <label>
        설명
        <textarea
          className="input"
          maxLength={1000}
          value={form.description}
          onChange={(e) => {
            setForm({ ...form, description: e.target.value });
            setSaved(false);
          }}
        />
      </label>
      {error && <ErrorBox error={error} />}
      <div className="row">
        <button className="btn btn-primary">{saved ? '저장됨' : '저장'}</button>
        <span className="spacer" />
        <button type="button" className="btn btn-danger" onClick={remove}>
          워크스페이스 삭제
        </button>
      </div>
    </form>
  );
}

function Avatar({ name, src }: { name: string; src: string | null }) {
  return src ? (
    <img className="avatar" src={src} alt="" referrerPolicy="no-referrer" />
  ) : (
    <span className="avatar avatar-text">{name.slice(0, 1)}</span>
  );
}
