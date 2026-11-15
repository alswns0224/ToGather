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
import { BTN_AI, BTN_DANGER, BTN_PRIMARY, BTN_SUB, CARD, EYEBROW, INPUT, MUTED, SUB_CARD } from '../../components/ui';

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
    <section className="mx-auto max-w-5xl space-y-6">
      <div className={`${CARD} p-7`}>
        <span className={EYEBROW}>TEAM</span>
        <h1 className="mt-2 !text-2xl">{workspace.data?.name ?? '팀원'}</h1>
        {workspace.data?.description && <p className={`m-0 mt-1 text-xs ${MUTED}`}>{workspace.data.description}</p>}
        {isOwner && <InviteLinkBox workspaceId={workspaceId} />}
      </div>

      {actionError && <ErrorBox error={actionError} />}
      {members.loading && <Loading />}
      {members.error && <ErrorBox error={members.error} onRetry={members.reload} />}
      {members.data && members.data.length === 0 && <Empty>팀원이 없습니다.</Empty>}
      {members.data && members.data.length > 0 && (
        <div className={`${CARD} grid grid-cols-1 gap-3 p-6 md:grid-cols-2`}>
          {members.data.map((m) => (
            <div key={m.userId} className={`${SUB_CARD} flex items-center justify-between gap-3 p-4`}>
              <div className="flex min-w-0 items-center gap-3">
                <Avatar name={m.name} src={m.profileImage} />
                <div className="min-w-0">
                  <p className="m-0 text-sm font-bold">
                    {m.name}{' '}
                    <span className="text-[10px] text-indigo-500">
                      ({m.role === 'OWNER' ? '팀장' : '팀원'}){m.userId === user.userId && ' · 나'}
                    </span>
                  </p>
                  <p className={`m-0 truncate text-xs ${MUTED}`}>{m.email}</p>
                </div>
              </div>
              {isOwner && m.role !== 'OWNER' && (
                <button className={BTN_DANGER} onClick={() => remove(m.userId, m.name)}>
                  내보내기
                </button>
              )}
            </div>
          ))}
        </div>
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
    <div className={`${SUB_CARD} mt-5 flex flex-wrap items-center justify-between gap-3 p-4`}>
      {active.loading && <Loading />}
      {error && <ErrorBox error={error} />}
      {current ? (
        <>
          <div className="min-w-0 flex-1">
            <p className="m-0 text-xs font-bold">🔗 팀원 초대 링크 (만료: {formatDateTime(current.expiresAt)})</p>
            <input
              className="mt-1 w-full border-0 bg-transparent p-0 text-xs font-bold text-indigo-500 outline-none"
              readOnly
              value={current.url}
              onFocus={(e) => e.target.select()}
            />
          </div>
          <div className="flex gap-2">
            <button className={BTN_SUB} onClick={create}>
              새 링크
            </button>
            <button className={BTN_PRIMARY} onClick={copy}>
              {copied ? '복사됨 ✓' : '링크 복사'}
            </button>
          </div>
        </>
      ) : (
        !active.loading && (
          <>
            <p className={`m-0 text-xs ${MUTED}`}>🔗 초대 링크를 만들어 팀원에게 보내세요. (7일간 유효)</p>
            <button className={BTN_PRIMARY} onClick={create}>
              초대 링크 만들기
            </button>
          </>
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
    <form className={`${CARD} space-y-4 p-7`} onSubmit={save}>
      <h2 className="!text-xl">워크스페이스 설정 (팀장)</h2>
      <label className="block">
        <span className={`mb-1 block text-xs font-bold ${MUTED}`}>이름</span>
        <input
          className={INPUT}
          required
          maxLength={100}
          value={form.name}
          onChange={(e) => {
            setForm({ ...form, name: e.target.value });
            setSaved(false);
          }}
        />
      </label>
      <label className="block">
        <span className={`mb-1 block text-xs font-bold ${MUTED}`}>설명</span>
        <textarea
          className={`${INPUT} min-h-20 resize-y`}
          maxLength={1000}
          value={form.description}
          onChange={(e) => {
            setForm({ ...form, description: e.target.value });
            setSaved(false);
          }}
        />
      </label>
      {error && <ErrorBox error={error} />}
      <div className="flex items-center">
        <button className={BTN_AI}>{saved ? '저장됨 ✓' : '저장하기'}</button>
        <span className="flex-1" />
        <button type="button" className={BTN_DANGER} onClick={remove}>
          워크스페이스 삭제
        </button>
      </div>
    </form>
  );
}

function Avatar({ name, src }: { name: string; src: string | null }) {
  return src ? (
    <img className="h-10 w-10 flex-shrink-0 rounded-full object-cover ring-2 ring-white dark:ring-slate-900" src={src} alt="" referrerPolicy="no-referrer" />
  ) : (
    <span className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full bg-indigo-600 text-xs font-bold text-white ring-2 ring-white dark:ring-slate-900">
      {name.slice(0, 2)}
    </span>
  );
}
