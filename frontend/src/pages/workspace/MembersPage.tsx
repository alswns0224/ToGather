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
import { BTN_AI, BTN_DANGER, CARD, INPUT, MUTED } from '../../components/ui';
import PageHeader from '../../components/PageHeader';
import NavIcon from '../../layouts/NavIcon';

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
      <PageHeader
        icon={<NavIcon name="members" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="팀원 관리"
        description={
          isOwner
            ? '초대 링크를 만들어 팀원을 초대하고 역할을 관리합니다.'
            : `${workspace.data?.name ?? ''} 팀원 목록입니다. 초대와 내보내기는 팀장만 할 수 있어요.`
        }
        actions={isOwner ? <InviteActions workspaceId={workspaceId} /> : undefined}
      />

      {actionError && <ErrorBox error={actionError} />}
      {members.loading && <Loading />}
      {members.error && <ErrorBox error={members.error} onRetry={members.reload} />}
      {members.data && members.data.length === 0 && <Empty>팀원이 없습니다.</Empty>}
      {members.data && members.data.length > 0 && (
        <div className="card overflow-hidden !p-0">
          <div className="members-row members-head">
            <span>이름 / 이메일</span>
            <span>참여일</span>
            <span>역할</span>
            <span>관리</span>
          </div>
          {members.data.map((m) => (
            <div key={m.userId} className="members-row">
              <div className="flex min-w-0 items-center gap-3">
                <Avatar name={m.name} src={m.profileImage} />
                <div className="min-w-0">
                  <div className="text-[15px] font-bold">{m.name}</div>
                  <div className="truncate text-[13px] text-[var(--muted)]">{m.email}</div>
                </div>
              </div>
              <span className="text-sm">{formatDateTime(m.joinedAt)}</span>
              <span>
                <span className={`badge ${m.role === 'OWNER' ? 'badge-primary' : ''}`}>
                  {m.role === 'OWNER' ? '관리자' : '구성원'}
                </span>
              </span>
              <span>
                {m.userId === user.userId ? (
                  <span className="text-[13px] text-[var(--muted)]">본인</span>
                ) : isOwner && m.role !== 'OWNER' ? (
                  <button type="button" className="btn btn-danger btn-small" onClick={() => remove(m.userId, m.name)}>
                    내보내기
                  </button>
                ) : (
                  <span className="text-[13px] text-[var(--muted)]">-</span>
                )}
              </span>
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

/** 머리 오른쪽: 초대 링크 복사(없으면 만들어서 복사) · 새 링크 만들기 */
function InviteActions({ workspaceId }: { workspaceId: string }) {
  const active = useAsync(() => getActiveInviteLink(workspaceId), [workspaceId]);
  const [link, setLink] = useState<InviteLinkResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const current = link ?? active.data ?? null;

  const create = async () => {
    setError(null);
    try {
      const next = await createInviteLink(workspaceId);
      setLink(next);
      return next;
    } catch (err) {
      setError((err as Error).message);
      return null;
    }
  };

  const copy = async () => {
    const target = current ?? (await create());
    if (!target) return;
    try {
      await navigator.clipboard.writeText(target.url);
      setCopied(true);
    } catch {
      window.prompt('초대 링크를 복사하세요.', target.url);
    }
  };

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex gap-2">
        <button type="button" className="btn" onClick={copy}>
          {copied ? '복사됨 ✓' : '초대 링크 복사'}
        </button>
        <button
          type="button"
          className="btn btn-primary"
          onClick={async () => {
            if ((await create()) !== null) setCopied(false);
          }}
        >
          새 초대 링크 만들기
        </button>
      </div>
      {current && (
        <span className="text-[11px] text-[var(--muted)]">초대 링크 만료 {formatDateTime(current.expiresAt)}</span>
      )}
      {error && <span className="text-[11px] text-[var(--danger)]">{error}</span>}
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
      `워크스페이스와 모든 데이터(팀 작업, 회의, 연동)가 삭제됩니다.\n삭제하려면 워크스페이스 이름 "${initial.name}"을 입력하세요.`,
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
    <form className={`${CARD} space-y-4 p-5`} onSubmit={save}>
      <strong className="text-[15px]">워크스페이스 설정 (팀장)</strong>
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
    <img className="h-9 w-9 flex-shrink-0 rounded-full object-cover" src={src} alt="" referrerPolicy="no-referrer" />
  ) : (
    <span className="tg-avatar !h-9 !w-9 !text-sm">{name.slice(0, 1)}</span>
  );
}
