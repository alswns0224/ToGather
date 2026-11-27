// UI-03 외부 협업 서비스 연동 관리 (담당: A) — 팀원 UI 시안(togather_ui.html)의 2열 서비스 카드
import { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import {
  PROVIDER_LABELS,
  connectUrl,
  disconnect,
  getAvailableTargets,
  getIntegrations,
  reauthUrl,
  selectTargets,
  syncNow,
  type ConnectionStatus,
  type ConnectionStatusDTO,
  type IntegrationStatusResponse,
  type TargetItem,
} from './api';
import { useAsync } from '../../hooks/useAsync';
import { getWorkspace } from '../workspace/api';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import ServiceLogo, { PROVIDER_LOGO } from '../../components/ServiceLogo';
import NavIcon from '../../layouts/NavIcon';

const STATUS_BADGE: Record<ConnectionStatus, { text: string; className: string }> = {
  CONNECTED: { text: '연결됨', className: 'badge-success' },
  TARGET_NOT_SELECTED: { text: '대상 선택 필요', className: 'badge-primary' },
  REAUTH_REQUIRED: { text: '재인증 필요', className: 'badge-warning' },
  DISCONNECTED: { text: '연결 전', className: '' },
};

const ERROR_MESSAGES: Record<string, string> = {
  CANCELED: '인증을 취소했습니다.',
  OAUTH_NOT_CONFIGURED: '서버에 이 서비스의 OAuth 설정(client id/secret)이 없습니다.',
  OAUTH_STATE_MISMATCH: '인증 요청이 만료되었습니다. 다시 시도해 주세요.',
  REAUTH_ACCOUNT_MISMATCH: '처음 연결한 계정과 다른 계정으로 인증했습니다. 같은 계정으로 다시 인증해 주세요.',
  EXTERNAL_API_ERROR: '외부 서비스와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  TOKEN_KEY_NOT_CONFIGURED: '서버에 토큰 암호화 키(TOGATHER_TOKEN_KEY)가 없습니다.',
};

export default function IntegrationsPage() {
  const { workspaceId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const { loading, error, data, reload } = useAsync(() => getIntegrations(workspaceId), [workspaceId]);
  // 팀 Drive의 모든 항목은 팀장 소유라 Drive 폴더는 팀장만 고른다.
  const workspace = useAsync(() => getWorkspace(workspaceId), [workspaceId]);
  const isMainOwner = workspace.data?.myRole === 'OWNER';
  const [selecting, setSelecting] = useState<ConnectionStatusDTO | null>(null);
  const [actionError, setActionError] = useState<Error>();
  const [removingId, setRemovingId] = useState<number | null>(null);
  const [disconnectNotice, setDisconnectNotice] = useState<{ text: string; warning: boolean } | null>(null);

  const connectedProvider = params.get('connected');
  const connectedId = params.get('connectionId');
  const errorCode = params.get('error');

  // 인증 직후 대상 선택이 필요하면 선택 창을 바로 연다.
  // Notion은 "페이지 추가"로 인증 화면에서 페이지를 더 공유하고 돌아오므로, 내 연결이면 바로 선택 창을 연다.
  useEffect(() => {
    if (!data || !connectedId) return;
    const item = data.find((i) => i.connections.some((c) => c.connectionId === Number(connectedId)));
    const conn = item?.connections.find((c) => c.connectionId === Number(connectedId));
    if (!item || !conn) return;
    const addingNotionPages = conn.provider === 'NOTION' && conn.mine && conn.status === 'CONNECTED';
    if (conn.status !== 'TARGET_NOT_SELECTED' && !addingNotionPages) return;
    setSelecting(item.personal ? conn : { ...conn, targets: teamTargetsOf(item) });
  }, [data, connectedId]);

  const clearParams = () => setParams({}, { replace: true });

  const remove = async (conn: ConnectionStatusDTO) => {
    const name = PROVIDER_LABELS[conn.provider].name;
    const google = ['GOOGLE_DRIVE', 'GOOGLE_CALENDAR', 'GMAIL'].includes(conn.provider);
    const scopeNotice = google ? '\n같은 Google 계정으로 연결한 다른 서비스도 재인증이 필요할 수 있습니다.' : '';
    if (!window.confirm(`${name} 연결을 해제할까요? 이 서비스의 콘텐츠를 더 이상 불러오지 않습니다.${scopeNotice}`)) return;
    setActionError(undefined);
    setDisconnectNotice(null);
    setRemovingId(conn.connectionId);
    try {
      const result = await disconnect(workspaceId, conn.connectionId);
      setDisconnectNotice({
        warning: result.manualRevocationRequired || result.relatedConnectionsRequireReauth,
        text: result.manualRevocationRequired
          ? `${name}의 ToGather 연결은 해제했지만 외부 권한 폐기를 확인하지 못했어요. ${name} 설정에서 ToGather 권한을 직접 확인·해제해 주세요.`
          : result.relatedConnectionsRequireReauth
            ? `${name} 연결을 해제했어요. 같은 Google 계정의 다른 연결은 다시 인증해 주세요.`
            : `${name} 연결을 해제했어요.`,
      });
      reload();
    } catch (err) {
      setActionError(err as Error);
    } finally {
      setRemovingId(null);
    }
  };

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="integrations" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="외부 서비스 연동 관리"
        description="모든 서비스는 각자 내 계정으로 연결해요. Slack · Notion · Drive는 팀이 고른 채널·페이지·폴더 중 내 계정으로 볼 수 있는 것만 보여요."
      />

      {connectedProvider && !errorCode && (
        <div className="alert alert-success">
          {PROVIDER_LABELS[connectedProvider as keyof typeof PROVIDER_LABELS]?.name ?? connectedProvider} 연결을
          완료했습니다.
          <button className="btn btn-small" onClick={clearParams}>
            닫기
          </button>
        </div>
      )}
      {errorCode && (
        <div className="alert alert-error">
          {ERROR_MESSAGES[errorCode] ?? `연동에 실패했습니다. (${errorCode})`}
          <button className="btn btn-small" onClick={clearParams}>
            닫기
          </button>
        </div>
      )}
      {actionError && <ErrorBox error={actionError} />}
      {disconnectNotice && (
        <div className={`alert ${disconnectNotice.warning ? '' : 'alert-success'}`} role="status">
          <span>{disconnectNotice.text}</span>
          <button type="button" className="btn btn-small" onClick={() => setDisconnectNotice(null)}>닫기</button>
        </div>
      )}
      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {data?.map((item) => (
          <ServiceCard
            key={item.provider}
            workspaceId={workspaceId}
            item={item}
            isMainOwner={isMainOwner}
            onSelect={setSelecting}
            onRemove={remove}
            removingId={removingId}
          />
        ))}
      </div>

      {selecting && (
        <TargetSelectDialog
          workspaceId={workspaceId}
          connection={selecting}
          onClose={() => setSelecting(null)}
          onSaved={() => {
            setSelecting(null);
            clearParams();
            reload();
          }}
        />
      )}
    </section>
  );
}

/** 서비스 하나: 로고 · 이름 · 팀 공용/개인 연동 · 상태 / 수집 대상 · 마지막 동기화 / 버튼 */
function ServiceCard({
  workspaceId,
  item,
  isMainOwner,
  onSelect,
  onRemove,
  removingId,
}: {
  workspaceId: string;
  item: IntegrationStatusResponse;
  isMainOwner: boolean;
  onSelect: (conn: ConnectionStatusDTO) => void;
  onRemove: (conn: ConnectionStatusDTO) => void;
  removingId: number | null;
}) {
  const label = PROVIDER_LABELS[item.provider];
  // 모든 서비스는 개인 연동이다. 카드의 상태·버튼은 "내 연결" 기준으로 보여 준다.
  const mine = item.connections.find((c) => c.mine);
  const badge = STATUS_BADGE[mine?.status ?? 'DISCONNECTED'];
  // 누가 연결했는지만 보여 준다. 팀 자료 서비스는 연결한 팀원들을, 개인 서비스는 나만.
  const others = item.personal ? [] : item.connections.filter((c) => !c.mine);
  // 예: "나, 이찬서 님, 정철준 님이 연결했어요" / 나만이면 "내가 연결했어요"
  const names = [...new Set([...(mine ? ['나'] : []), ...others.map((c) => `${c.connectedByName ?? '팀원'} 님`)])];
  const connectedBy =
    names.length === 0
      ? '아직 연결한 사람이 없어요'
      : `${names.length === 1 && names[0] === '나' ? '내가' : `${names.join(', ')}이`} 연결했어요`;

  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  const sync = async () => {
    setSyncing(true);
    setSyncError(null);
    try {
      await syncNow(workspaceId, item.provider);
    } catch (e) {
      setSyncError((e as Error).message);
    } finally {
      setSyncing(false);
    }
  };

  return (
    <div className="card integ-card">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span className="inline-flex h-10 w-10 flex-shrink-0">
            <ServiceLogo service={PROVIDER_LOGO[item.provider]} />
          </span>
          <div className="min-w-0">
            <div className="flex flex-wrap items-baseline gap-1">
              <strong className="text-[19px]">{label.name}</strong>
            </div>
            <div className="truncate text-xs text-[var(--muted)]">{connectedBy}</div>
          </div>
        </div>
        <span className={`badge ${badge.className}`}>{badge.text}</span>
      </div>

      {(item.parkedTargets?.length ?? 0) > 0 && (
        <div className="alert small">
          지금 팀원 누구도 쓰지 못하는 {label.target || '대상'}: <strong>{item.parkedTargets!.join(', ')}</strong>
          <br />
          {item.provider === 'SLACK'
            ? 'Slack에서 그 채널에 다시 참여하거나, 볼 수 있는 계정으로 다시 연결하면 그대로 되살아나요.'
            : `이 ${label.target || '대상'}을(를) 볼 수 있는 계정으로 다시 연결하면 그대로 되살아나요.`}
        </div>
      )}
      {syncError && <div className="alert alert-error small">동기화하지 못했습니다. {syncError}</div>}

      <div className="flex flex-wrap gap-2">
        {!mine ? (
          item.configured ? (
            <a className="btn btn-primary" href={connectUrl(workspaceId, item.provider)}>
              내 계정 연결
            </a>
          ) : (
            <span className="self-center text-xs text-[var(--muted)]">서버 설정 필요</span>
          )
        ) : (
          <>
            <button type="button" className="btn" onClick={sync} disabled={syncing}>
              {syncing ? '동기화 중...' : '수동 동기화'}
            </button>
            {mine.status === 'REAUTH_REQUIRED' ? (
              <a className="btn btn-primary" href={reauthUrl(workspaceId, mine.connectionId)}>
                재인증하기
              </a>
            ) : item.provider === 'GOOGLE_DRIVE' && !isMainOwner ? (
              <span className="self-center text-xs text-[var(--muted)]">Drive 폴더는 팀장이 골라요</span>
            ) : label.target ? (
              <button
                type="button"
                className="btn btn-primary"
                onClick={() =>
                  // 팀 자료 서비스는 팀 전체에 목록이 하나라, 지금 팀이 쓰는 대상을 체크한 채로 연다.
                  onSelect(item.personal ? mine : { ...mine, targets: teamTargetsOf(item) })
                }
              >
                {label.target} 선택
              </button>
            ) : null}
            {item.provider === 'NOTION' && mine.status !== 'REAUTH_REQUIRED' && (
              // Notion은 인증 화면에서 공유한 페이지만 고를 수 있다. 같은 Notion 워크스페이스로 다시 인증해 페이지를 더 공유한다.
              <a
                className="btn"
                href={reauthUrl(workspaceId, mine.connectionId)}
                title="Notion 인증 화면에서 페이지를 더 공유한 뒤 돌아와 고를 수 있어요"
              >
                페이지 추가
              </a>
            )}
            <button
              type="button"
              className="btn btn-danger"
              onClick={() => onRemove(mine)}
              disabled={syncing || removingId !== null}
            >
              {removingId === mine.connectionId ? '연결 해제 중...' : syncing ? '동기화 중' : '연결 해제'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

/** 팀 자료 서비스는 팀 전체에 대상 목록이 하나라, 팀원들이 고른 대상을 모아 선택 창에 체크해 둔다. */
function teamTargetsOf(item: IntegrationStatusResponse) {
  return [...new Map(item.connections.flatMap((c) => c.targets).map((t) => [t.id, t])).values()];
}

/**
 * Drive 폴더 목록을 상위 폴더 기준 트리 순서로 펼친다. 상위 폴더가 목록에 없으면(내 드라이브 바로 아래 등) 맨 위로 둔다.
 * descendants(id)는 그 폴더 아래 모든 폴더 ID.
 */
function buildFolderTree(options: TargetItem[]) {
  const ids = new Set(options.map((o) => o.id));
  const children = new Map<string, TargetItem[]>();
  const roots: TargetItem[] = [];
  for (const o of options) {
    if (o.parentId && ids.has(o.parentId) && o.parentId !== o.id) {
      children.set(o.parentId, [...(children.get(o.parentId) ?? []), o]);
    } else {
      roots.push(o);
    }
  }
  const byName = (a: TargetItem, b: TargetItem) => (a.name ?? a.id).localeCompare(b.name ?? b.id, 'ko');
  const ordered: { option: TargetItem; depth: number }[] = [];
  const seen = new Set<string>();
  const walk = (o: TargetItem, depth: number) => {
    if (seen.has(o.id)) return;
    seen.add(o.id);
    ordered.push({ option: o, depth });
    [...(children.get(o.id) ?? [])].sort(byName).forEach((c) => walk(c, depth + 1));
  };
  roots.forEach((r) => walk(r, 0));
  // 상위 폴더끼리 서로를 가리키는 이상한 경우에도 빠지는 폴더가 없게 한다.
  options.forEach((o) => walk(o, 0));

  const descendants = (id: string): string[] => {
    const result: string[] = [];
    const stack = [...(children.get(id) ?? [])];
    const visited = new Set<string>([id]);
    while (stack.length > 0) {
      const c = stack.pop()!;
      if (visited.has(c.id)) continue;
      visited.add(c.id);
      result.push(c.id);
      stack.push(...(children.get(c.id) ?? []));
    }
    return result;
  };
  return { ordered, descendants };
}

function TargetSelectDialog({
  workspaceId,
  connection,
  onClose,
  onSaved,
}: {
  workspaceId: string;
  connection: ConnectionStatusDTO;
  onClose: () => void;
  onSaved: () => void;
}) {
  const label = PROVIDER_LABELS[connection.provider];
  const options = useAsync(
    () => getAvailableTargets(workspaceId, connection.connectionId),
    [workspaceId, connection.connectionId],
  );
  const [selected, setSelected] = useState<Set<string>>(new Set(connection.targets.map((t) => t.id)));
  const [error, setError] = useState<Error>();
  const [saving, setSaving] = useState(false);
  // Drive 폴더는 트리로 보여 주고, 폴더를 고르거나 빼면 그 아래 폴더도 함께 고르거나 뺀다.
  const isDrive = connection.provider === 'GOOGLE_DRIVE';
  const tree = buildFolderTree(isDrive ? options.data ?? [] : []);
  const rows: { option: TargetItem; depth: number }[] = isDrive
    ? tree.ordered
    : (options.data ?? []).map((option) => ({ option, depth: 0 }));

  const toggle = (id: string) => {
    const next = new Set(selected);
    const ids = isDrive ? [id, ...tree.descendants(id)] : [id];
    if (selected.has(id)) ids.forEach((i) => next.delete(i));
    else ids.forEach((i) => next.add(i));
    setSelected(next);
  };

  const save = async () => {
    setSaving(true);
    setError(undefined);
    try {
      const targets: TargetItem[] = (options.data ?? [])
        .filter((o) => selected.has(o.id))
        .map((o) => ({ id: o.id, name: o.name }));
      await selectTargets(workspaceId, connection.connectionId, targets);
      onSaved();
    } catch (err) {
      setError(err as Error);
      setSaving(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal stack" onClick={(e) => e.stopPropagation()}>
        <h2>
          {label.name} {label.target} 선택
        </h2>
        <p className="muted small">
          {`워크스페이스에서 사용할 ${label.target}을(를) 고르세요.`}
          {isDrive && ' 폴더를 고르면 그 아래 폴더도 함께 골라져요.'}
        </p>
        {options.loading && <Loading />}
        {options.error && <ErrorBox error={options.error} onRetry={options.reload} />}
        {options.data && options.data.length === 0 && (
          <Empty>
            선택할 수 있는 {label.target}이(가) 없습니다.
            {connection.provider === 'NOTION' && ' Notion 연결 화면에서 페이지나 데이터베이스를 공유했는지 확인하세요.'}
          </Empty>
        )}
        {connection.provider === 'NOTION' && options.data && options.data.length > 0 && (
          <p className="muted small">
            찾는 페이지가 없으면 카드의 <strong>페이지 추가</strong>를 눌러 Notion 인증 화면에서 페이지를 더 공유하세요.
          </p>
        )}
        {options.data && options.data.length > 0 && (
          <ul className="list scroll">
            {rows.map(({ option: o, depth }) => (
              <li key={o.id} style={depth > 0 ? { paddingLeft: depth * 20 } : undefined}>
                <label className="row">
                  <input
                    type="checkbox"
                    checked={selected.has(o.id)}
                    onChange={() => toggle(o.id)}
                  />
                  {o.name ?? o.id}
                </label>
              </li>
            ))}
          </ul>
        )}
        {error && <ErrorBox error={error} />}
        <div className="row">
          <button className="btn btn-primary" onClick={save} disabled={saving || selected.size === 0}>
            저장
          </button>
          <span className="spacer" />
          <button className="btn" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
}
