// UI-03 외부 협업 서비스 연동 관리 (담당: A)
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
  skipTargets,
  type ConnectionStatus,
  type ConnectionStatusDTO,
  type TargetItem,
} from './api';
import { useAsync } from '../../hooks/useAsync';
import { Empty, ErrorBox, Loading } from '../../components/Status';

const STATUS_LABELS: Record<ConnectionStatus, { text: string; className: string }> = {
  CONNECTED: { text: '연결됨', className: 'badge-success' },
  TARGET_NOT_SELECTED: { text: '대상 선택 필요', className: 'badge-warning' },
  REAUTH_REQUIRED: { text: '재인증 필요', className: 'badge-danger' },
  DISCONNECTED: { text: '연결 안 됨', className: '' },
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
      <h1>연동 관리</h1>
      <p className="muted">
        Slack · Notion · Drive는 팀에서 한 명이 연결하면 모두가 사용합니다. Gmail · Calendar는 각자 연결합니다.
      </p>

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

      {data?.map((item) => {
        const label = PROVIDER_LABELS[item.provider];
        const mine = item.connections.find((c) => c.mine);
        return (
          <div key={item.provider} className="card stack">
            <div className="row">
              <strong>{label.name}</strong>
              {item.personal && <span className="badge">개인</span>}
              <span className="spacer" />
              {item.configured ? (
                <a className="btn btn-small" href={connectUrl(workspaceId, item.provider)}>
                  {mine ? '다른 계정으로 다시 연결' : '연결하기'}
                </a>
              ) : (
                <span className="muted small">서버 설정 필요</span>
              )}
            </div>
            <p className="muted small">{label.description}</p>

            {item.connections.length === 0 && <Empty>연결되지 않았습니다.</Empty>}
            {item.connections.map((conn) => {
              const status = STATUS_LABELS[conn.status];
              return (
                <div key={conn.connectionId} className="connection">
                  <div className="row">
                    <span className={`badge ${status.className}`}>{status.text}</span>
                    <span className="small">
                      {conn.accountLabel ?? conn.externalAccountId ?? '-'} · {conn.mine ? '내가 연결' : `${conn.connectedByName}님이 연결`}
                    </span>
                    <span className="spacer" />
                    {conn.status === 'REAUTH_REQUIRED' && conn.mine && (
                      <a className="btn btn-small btn-primary" href={reauthUrl(workspaceId, conn.connectionId)}>
                        재인증
                      </a>
                    )}
                    {label.target && (
                      <button className="btn btn-small" onClick={() => setSelecting(conn)}>
                        {label.target} 선택
                      </button>
                    )}
                    <button className="btn btn-small btn-danger" onClick={() => remove(conn)}>
                      해제
                    </button>
                  </div>
                  {conn.targets.length > 0 && (
                    <div className="tags">
                      {conn.targets.map((t) => (
                        <span key={t.id} className="tag">
                          {t.name ?? t.id}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        );
      })}

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
            {connection.provider === 'NOTION' && ' Notion에서 데이터베이스를 이 통합에 공유했는지 확인하세요.'}
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
