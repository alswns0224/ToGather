// UI-02 팀원 관리 · 워크스페이스 설정 (담당: A)
import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import {
  changeMemberRole,
  createInviteLink,
  deleteWorkspace,
  getActiveInviteLink,
  getMembers,
  getWorkspace,
  removeMember,
  transferOwner,
  updateWorkspace,
  type InviteLinkResponse,
  type MemberResponse,
  type WorkspaceRole,
} from './api';
import { useAsync } from '../../hooks/useAsync';
import { getDriveOwnership } from '../drive/api';
import { useLoginUser } from '../../components/RequireAuth';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import { formatDateTime } from '../../utils/date';
import { BTN_AI, BTN_DANGER, CARD, INPUT, MUTED } from '../../components/ui';
import PageHeader from '../../components/PageHeader';
import NavIcon from '../../layouts/NavIcon';

const ROLE_LABEL: Record<WorkspaceRole, string> = { OWNER: '팀장', ADMIN: '관리자', MEMBER: '팀원' };

export default function MembersPage() {
  const { workspaceId = '' } = useParams();
  const user = useLoginUser();
  const navigate = useNavigate();
  const workspace = useAsync(() => getWorkspace(workspaceId), [workspaceId]);
  const members = useAsync(() => getMembers(workspaceId), [workspaceId]);
  // 팀장을 넘기면 Drive 파일 소유권도 새 팀장에게 넘어간다. 진행 상황을 보여 준다.
  const ownership = useAsync(() => getDriveOwnership(workspaceId).catch(() => null), [workspaceId]);
  const transfer = ownership.data?.transfer;
  // 팀장: 관리자 임명·해제, 팀장 넘기기, 워크스페이스 수정·삭제까지 / 관리자: 초대 링크 관리, 일반 팀원 내보내기
  const isMainOwner = workspace.data?.myRole === 'OWNER';
  const isAdmin = isMainOwner || workspace.data?.myRole === 'ADMIN';
  const [actionError, setActionError] = useState<Error>();
  // 내보내면 서버가 초대 링크를 새로 만들므로, 머리의 초대 링크를 다시 불러온다
  const [inviteVersion, setInviteVersion] = useState(0);

  const remove = async (userId: number, name: string) => {
    if (!window.confirm(`${name}님을 워크스페이스에서 내보낼까요?\n기존 초대 링크는 더 이상 쓸 수 없고 새 링크로 바뀝니다.`)) return;
    setActionError(undefined);
    try {
      members.setData(await removeMember(workspaceId, userId));
      setInviteVersion((v) => v + 1);
    } catch (err) {
      setActionError(err as Error);
    }
  };

  // 역할 변경 · 권한 넘기기: 내 권한이 바뀔 수 있으므로 워크스페이스(myRole)도 다시 불러온다
  const runRoleAction = async (message: string, action: () => Promise<MemberResponse[]>) => {
    if (!window.confirm(message)) return;
    setActionError(undefined);
    try {
      members.setData(await action());
      workspace.reload();
    } catch (err) {
      setActionError(err as Error);
    }
  };

  const memberActions = (m: MemberResponse) => {
    const isMe = m.userId === user.userId;
    const none = <span className="text-[13px] text-[var(--muted)]">{isMe ? '본인' : '-'}</span>;
    if (m.role === 'OWNER') return none;
    if (m.role === 'ADMIN') {
      if (!isMe && !isMainOwner) return none;
      return (
        <button
          type="button"
          className="btn btn-small"
          onClick={() =>
            runRoleAction(
              isMe ? '관리자를 내려놓을까요? 팀원이 됩니다.' : `${m.name}님을 관리자에서 해제할까요?`,
              () => changeMemberRole(workspaceId, m.userId, 'MEMBER'),
            )
          }
        >
          {isMe ? '관리자 내려놓기' : '관리자 해제'}
        </button>
      );
    }
    if (!isAdmin) return none;
    return (
      <div className="flex flex-wrap gap-1.5">
        {isMainOwner && (
          <>
            <button
              type="button"
              className="btn btn-small"
              onClick={() =>
                runRoleAction(`${m.name}님을 관리자로 임명할까요?
초대 링크 관리와 팀원 내보내기를 할 수 있게 됩니다.`, () =>
                  changeMemberRole(workspaceId, m.userId, 'ADMIN'),
                )
              }
            >
              관리자 임명
            </button>
            <button
              type="button"
              className="btn btn-small"
              onClick={() =>
                runRoleAction(
                  `${m.name}님에게 팀장을 넘길까요?\n넘기면 나는 팀원이 됩니다.\n\n`
                    + '팀 Drive의 파일·폴더 소유권도 새 팀장에게 차례로 넘어갑니다. '
                    + '새 팀장이 Google Drive를 연동해 두어야 하고, 넘기는 동안에는 두 사람의 Drive 연결을 해제할 수 없어요.',
                  () => transferOwner(workspaceId, m.userId),
                ).then(() => ownership.reload())
              }
            >
              팀장 넘기기
            </button>
          </>
        )}
        <button type="button" className="btn btn-danger btn-small" onClick={() => remove(m.userId, m.name)}>
          내보내기
        </button>
      </div>
    );
  };

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="members" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="팀원 관리"
        description={
          isMainOwner
            ? '초대 링크로 팀원을 초대하고, 관리자 임명·팀장 넘기기로 역할을 관리합니다.'
            : isAdmin
              ? '관리자는 초대 링크를 관리하고 팀원을 내보낼 수 있어요. 역할 변경과 워크스페이스 수정·삭제는 팀장만 할 수 있어요.'
              : `${workspace.data?.name ?? ''} 팀원 목록입니다. 초대와 내보내기는 팀장·관리자만 할 수 있어요.`
        }
        actions={isAdmin ? <InviteActions key={inviteVersion} workspaceId={workspaceId} /> : undefined}
      />

      {actionError && <ErrorBox error={actionError} />}
      {transfer?.status === 'RUNNING' && (
        <div role="status" className="alert">
          Drive 파일 소유권을 {transfer.fromName ?? '이전 팀장'} 님에서 {transfer.toName ?? '새 팀장'} 님에게 넘기는 중이에요.
          ({transfer.doneCount}개 완료{transfer.remainingCount > 0 && `, ${transfer.remainingCount}개 남음`})
          <button type="button" className="btn btn-small" onClick={() => ownership.reload()}>새로고침</button>
        </div>
      )}
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
                <span className={`badge ${m.role === 'MEMBER' ? '' : 'badge-primary'}`}>{ROLE_LABEL[m.role]}</span>
              </span>
              <span>{memberActions(m)}</span>
            </div>
          ))}
        </div>
      )}

      {isMainOwner && workspace.data && (
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
