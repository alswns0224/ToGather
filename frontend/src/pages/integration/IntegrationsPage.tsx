// UI-03 외부 협업 서비스 연동 관리 (담당: A) — 팀원 UI 시안(togather_ui.html)의 2열 서비스 카드
import { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import {
  PROVIDER_LABELS,
  connectUrl,
  disconnect,
  getAvailableTargets,
  getIntegrations,
  getSyncStatuses,
  reauthUrl,
  selectTargets,
  skipTargets,
  syncNow,
  type ConnectionStatus,
  type ConnectionStatusDTO,
  type IntegrationStatusResponse,
  type TargetItem,
} from './api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import PageHeader from '../../components/PageHeader';
import ServiceLogo, { PROVIDER_LOGO } from '../../components/ServiceLogo';
import NavIcon from '../../layouts/NavIcon';
import { formatDateTime } from '../../utils/date';

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
  const [selecting, setSelecting] = useState<ConnectionStatusDTO | null>(null);
  const [actionError, setActionError] = useState<Error>();
  const [removingId, setRemovingId] = useState<number | null>(null);
  const [disconnectNotice, setDisconnectNotice] = useState<{ text: string; warning: boolean } | null>(null);

  const connectedProvider = params.get('connected');
  const connectedId = params.get('connectionId');
  const errorCode = params.get('error');

  // 인증 직후 대상 선택이 필요하면 선택 창을 바로 연다.
  useEffect(() => {
    if (!data || !connectedId) return;
    const conn = data.flatMap((i) => i.connections).find((c) => c.connectionId === Number(connectedId));
    if (conn && conn.status === 'TARGET_NOT_SELECTED') setSelecting(conn);
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
  onSelect,
  onRemove,
  removingId,
}: {
  workspaceId: string;
  item: IntegrationStatusResponse;
  onSelect: (conn: ConnectionStatusDTO) => void;
  onRemove: (conn: ConnectionStatusDTO) => void;
  removingId: number | null;
}) {
  const label = PROVIDER_LABELS[item.provider];
  // 모든 서비스는 개인 연동이다. 카드의 상태·버튼은 "내 연결" 기준으로 보여 준다.
  const mine = item.connections.find((c) => c.mine);
  const badge = STATUS_BADGE[mine?.status ?? 'DISCONNECTED'];
  // 팀 자료 서비스(Slack·Notion·Drive): 팀원들이 고른 연동 대상을 모아 보여 준다.
  const teamTargets = item.personal
    ? []
    : item.connections.flatMap((c) =>
        c.targets.map((t) => ({ name: t.name ?? t.id, by: c.mine ? '나' : `${c.connectedByName ?? '팀원'}` })),
      );

  // 누가 연결했는지: 팀 자료 서비스는 연결한 팀원들을, 개인 서비스는 내 계정만 보여 준다.
  const others = item.personal ? [] : item.connections.filter((c) => !c.mine);
  const account = (mine ?? others[0])?.accountLabel ?? (mine ?? others[0])?.externalAccountId;
  const names = [...(mine ? ['내가'] : []), ...others.map((c) => `${c.connectedByName ?? '팀원'}님이`)];
  const connectedBy =
    names.length === 0
      ? '아직 연결한 사람이 없어요'
      : `${account ? `${account} · ` : ''}${[...new Set(names)].join(', ')} 연결`;

  const statuses = useAsync(
    () => (mine ? getSyncStatuses(workspaceId, item.provider) : Promise.resolve([])),
    [workspaceId, item.provider, mine?.connectionId],
  );
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  const lastSynced = (statuses.data ?? [])
    .map((s) => s.lastSyncedAt)
    .filter((v): v is string => !!v)
    .sort()
    .pop();
  const syncFailed = (statuses.data ?? []).some((s) => s.failed);

  const sync = async () => {
    setSyncing(true);
    setSyncError(null);
    try {
      statuses.setData(await syncNow(workspaceId, item.provider));
    } catch (e) {
      setSyncError((e as Error).message);
    } finally {
      setSyncing(false);
    }
  };

  const myTargetText = mine
    ? mine.targets.length > 0
      ? mine.targets.map((t) => t.name ?? t.id).join(', ')
      : label.target
        ? '미선택'
        : '내 메일함'
    : '-';

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

      {mine && (
        <div className="text-[13px] text-[var(--muted)]">
          {item.personal ? '수집 대상' : `연동한 ${label.target}`}:{' '}
          <strong className="text-[var(--text)]">
            {item.personal
              ? myTargetText
              : teamTargets.length > 0
                ? [...new Set(teamTargets.map((t) => t.name))].join(', ')
                : '미선택'}
          </strong>{' '}
          · 마지막 동기화:{' '}
          {lastSynced ? formatDateTime(lastSynced) : '-'}
          {syncFailed && <span className="text-[var(--warning)]"> (최근 실패)</span>}
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
            ) : label.target ? (
              <button type="button" className="btn btn-primary" onClick={() => onSelect(mine)}>
                {label.target} 선택
              </button>
            ) : null}
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
  // 팀 캘린더는 하나만 고른다.
  const single = connection.provider === 'GOOGLE_CALENDAR';
  const optional = connection.provider === 'GOOGLE_CALENDAR';

  const toggle = (id: string) => {
    const next = new Set(single ? [] : selected);
    if (selected.has(id) && !single) next.delete(id);
    else next.add(id);
    setSelected(next);
  };

  const save = async () => {
    setSaving(true);
    setError(undefined);
    try {
      const targets: TargetItem[] = (options.data ?? []).filter((o) => selected.has(o.id));
      await selectTargets(workspaceId, connection.connectionId, targets);
      onSaved();
    } catch (err) {
      setError(err as Error);
      setSaving(false);
    }
  };

  const skip = async () => {
    try {
      await skipTargets(workspaceId, connection.connectionId);
      onSaved();
    } catch (err) {
      setError(err as Error);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal stack" onClick={(e) => e.stopPropagation()}>
        <h2>
          {label.name} {label.target} 선택
        </h2>
        <p className="muted small">
          {optional
            ? '팀 일정을 등록할 캘린더를 고르세요. 고르지 않으면 각자 기본 캘린더를 사용합니다.'
            : `워크스페이스에서 사용할 ${label.target}을(를) 고르세요.`}
        </p>
        {options.loading && <Loading />}
        {options.error && <ErrorBox error={options.error} onRetry={options.reload} />}
        {options.data && options.data.length === 0 && (
          <Empty>
            선택할 수 있는 {label.target}이(가) 없습니다.
            {connection.provider === 'NOTION' && ' Notion 연결 화면에서 페이지나 데이터베이스를 공유했는지 확인하세요.'}
          </Empty>
        )}
        {options.data && options.data.length > 0 && (
          <ul className="list scroll">
            {options.data.map((o) => (
              <li key={o.id}>
                <label className="row">
                  <input
                    type={single ? 'radio' : 'checkbox'}
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
          {optional && (
            <button className="btn" onClick={skip}>
              선택하지 않기
            </button>
          )}
          <span className="spacer" />
          <button className="btn" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
}
