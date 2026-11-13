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
    if (!window.confirm(`${name} 연결을 해제할까요? 이 서비스의 콘텐츠를 더 이상 불러오지 않습니다.`)) return;
    setActionError(undefined);
    try {
      await disconnect(workspaceId, conn.connectionId);
      reload();
    } catch (err) {
      setActionError(err as Error);
    }
  };

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="integrations" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="외부 서비스 연동 관리"
        description="수집 대상을 고르고, 수동 동기화·재인증·연결 해제를 할 수 있어요. Slack · Notion · Drive는 팀 공용, Gmail · Calendar는 각자 연결해요."
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
}: {
  workspaceId: string;
  item: IntegrationStatusResponse;
  onSelect: (conn: ConnectionStatusDTO) => void;
  onRemove: (conn: ConnectionStatusDTO) => void;
}) {
  const label = PROVIDER_LABELS[item.provider];
  const mine = item.connections.find((c) => c.mine);
  // 대표 연결: 개인 서비스는 내 연결, 팀 서비스는 첫 연결
  const primary = item.personal ? mine : item.connections[0];
  const status = primary?.status ?? 'DISCONNECTED';
  const badge = STATUS_BADGE[status];

  const statuses = useAsync(
    () => (primary ? getSyncStatuses(workspaceId, item.provider) : Promise.resolve([])),
    [workspaceId, item.provider, primary?.connectionId],
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

  const targetText = primary
    ? primary.targets.length > 0
      ? primary.targets.map((t) => t.name ?? t.id).join(', ')
      : label.target
        ? '미선택'
        : '내 메일함'
    : '미선택';

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
              <span className="text-[13px] text-[var(--muted)]">· {item.personal ? '개인 연동' : '팀 공용'}</span>
            </div>
            {primary && (
              <div className="truncate text-xs text-[var(--muted)]">
                {primary.accountLabel ?? primary.externalAccountId ?? '-'} ·{' '}
                {primary.mine ? '내가 연결' : `${primary.connectedByName}님이 연결`}
              </div>
            )}
          </div>
        </div>
        <span className={`badge ${badge.className}`}>{badge.text}</span>
      </div>

      <div className="text-[13px] text-[var(--muted)]">
        수집 대상: <strong className="text-[var(--text)]">{targetText}</strong> · 마지막 동기화:{' '}
        {primary ? (lastSynced ? formatDateTime(lastSynced) : '-') : '-'}
        {syncFailed && <span className="text-[var(--warning)]"> (최근 실패)</span>}
      </div>

      {item.connections.length > 1 && (
        <div className="flex flex-col gap-1 text-xs text-[var(--muted)]">
          {item.connections
            .filter((c) => c !== primary)
            .map((c) => (
              <div key={c.connectionId} className="flex items-center gap-2">
                <span className={`badge ${STATUS_BADGE[c.status].className} !text-[10px]`}>{STATUS_BADGE[c.status].text}</span>
                {c.accountLabel ?? c.externalAccountId ?? '-'} · {c.mine ? '내가 연결' : `${c.connectedByName}님이 연결`}
                {c.mine && (
                  <button type="button" className="link-button" onClick={() => onRemove(c)}>
                    해제
                  </button>
                )}
              </div>
            ))}
        </div>
      )}

      {syncError && <div className="alert alert-error small">동기화하지 못했습니다. {syncError}</div>}

      <div className="flex flex-wrap gap-2">
        {primary && (
          <button type="button" className="btn" onClick={sync} disabled={syncing}>
            {syncing ? '동기화 중...' : '수동 동기화'}
          </button>
        )}
        {primary?.status === 'REAUTH_REQUIRED' && primary.mine ? (
          <a className="btn btn-primary" href={reauthUrl(workspaceId, primary.connectionId)}>
            재인증하기
          </a>
        ) : primary && label.target ? (
          <button type="button" className="btn btn-primary" onClick={() => onSelect(primary)}>
            {label.target} 선택
          </button>
        ) : item.configured ? (
          <a className="btn btn-primary" href={connectUrl(workspaceId, item.provider)}>
            {mine ? '다른 계정으로 다시 연결' : '연결하기'}
          </a>
        ) : (
          <span className="self-center text-xs text-[var(--muted)]">서버 설정 필요</span>
        )}
        {primary && !item.personal && !mine && item.configured && (
          <a className="btn" href={connectUrl(workspaceId, item.provider)}>
            내 계정 추가 연결
          </a>
        )}
        {primary && (
          <button type="button" className="btn btn-danger" onClick={() => onRemove(primary)}>
            연결 해제
          </button>
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
