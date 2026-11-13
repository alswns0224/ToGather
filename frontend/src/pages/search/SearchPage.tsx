// UI-12 통합 검색 (담당: B, C-07·08 이관)
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { search, type SearchItem, type SearchProvider, type SearchResponse } from './api';
import { ApiError } from '../../api/client';
import PageHeader from '../../components/PageHeader';
import { ErrorBox } from '../../components/Status';
import { MUTED } from '../../components/ui';
import NavIcon from '../../layouts/NavIcon';
import { formatDateTime } from '../../utils/date';

const PROVIDERS: { key: SearchProvider; label: string }[] = [
  { key: 'SLACK', label: 'Slack' },
  { key: 'NOTION', label: 'Notion' },
  { key: 'GOOGLE_DRIVE', label: 'Drive' },
];

const EXAMPLES = ['발표 담당은 누구로 정했지?', '리허설 일정', '시연 영상 마감'];

export default function SearchPage() {
  const { workspaceId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const query = params.get('q') ?? '';
  const providers = (params.get('providers')?.split(',').filter(Boolean) ?? []) as SearchProvider[];
  const [input, setInput] = useState(query);
  const [state, setState] = useState<{ loading: boolean; data?: SearchResponse; error?: Error }>({ loading: false });

  useEffect(() => setInput(query), [query]);

  const providerKey = providers.join(',');
  useEffect(() => {
    if (!query.trim()) {
      setState({ loading: false });
      return;
    }
    let cancelled = false;
    setState({ loading: true });
    search(workspaceId, query, providers)
      .then((data) => !cancelled && setState({ loading: false, data }))
      .catch((error: Error) => !cancelled && setState({ loading: false, error }));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId, query, providerKey]);

  const update = (q: string, next: SearchProvider[]) => {
    const p = new URLSearchParams();
    if (q.trim()) p.set('q', q.trim());
    if (next.length > 0) p.set('providers', next.join(','));
    setParams(p);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    update(input, providers);
  };

  const only = (key: SearchProvider | null) => update(query, key ? [key] : []);
  const activeTab = providers.length === 1 ? providers[0] : null;

  return (
    <section className="stack">
      <PageHeader
        icon={<NavIcon name="search" className="h-[22px] w-[22px] text-[var(--primary)]" />}
        title="통합 검색"
        description="대화·문서·파일을 키워드가 아니라 문맥으로 찾아요. (BGE-M3 임베딩 · Chroma)"
      />

      <form onSubmit={submit} className="card flex flex-col gap-3">
        <input
          className="input !h-[42px] !text-[15px]"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="검색어를 입력하고 Enter (예: 발표 담당은 누구로 정했지?)"
          maxLength={200}
          autoFocus
        />
        <div className="segmented self-start">
          <button type="button" className={activeTab === null ? 'active' : ''} onClick={() => only(null)}>
            전체
          </button>
          {PROVIDERS.map((p) => (
            <button key={p.key} type="button" className={activeTab === p.key ? 'active' : ''} onClick={() => only(p.key)}>
              {p.label}
            </button>
          ))}
        </div>
      </form>

      {!query.trim() ? (
        <div className="card flex flex-col gap-3">
          <strong className="text-sm">이렇게 물어보세요</strong>
          <div className="flex flex-wrap gap-2">
            {EXAMPLES.map((ex) => (
              <button key={ex} type="button" className="search-example" onClick={() => update(ex, providers)}>
                {ex}
              </button>
            ))}
          </div>
          <p className={`m-0 text-xs ${MUTED}`}>
            단어가 정확히 같지 않아도 뜻이 비슷한 대화 · 문서 · 파일을 찾아 관련도순으로 보여줍니다.
          </p>
        </div>
      ) : state.loading ? (
        <div className="card text-sm">의미 기반 벡터 검색 중...</div>
      ) : state.error ? (
        <ErrorBox
          error={
            state.error instanceof ApiError && state.error.code === 'SEARCH_UNAVAILABLE'
              ? new Error('검색 서버(BGE-M3 · Chroma)가 꺼져 있어요. 서버 관리자에게 알려주세요.')
              : state.error
          }
          onRetry={() => update(query, providers)}
        />
      ) : state.data ? (
        <Results workspaceId={workspaceId} data={state.data} />
      ) : null}
    </section>
  );
}

function Results({ workspaceId, data }: { workspaceId: string; data: SearchResponse }) {
  return (
    <>
      <p className={`m-0 text-xs ${MUTED}`}>
        "{data.query}" 검색 결과 {data.items.length}건
        {data.pending > 0 && ` · 아직 검색에 반영 중인 콘텐츠 ${data.pending}건`}
      </p>
      <div className="card overflow-hidden !p-0">
        {data.items.length === 0 ? (
          <div className="p-8 text-center text-sm text-[var(--muted)]">
            검색 결과가 없습니다. 다른 표현으로 물어보거나 출처를 "전체"로 바꿔 보세요.
            {data.pending > 0 && ' 최근 내용은 1~2분 뒤 검색됩니다.'}
          </div>
        ) : (
          data.items.map((item) => <ResultRow key={item.contentRefId} workspaceId={workspaceId} item={item} />)
        )}
      </div>
    </>
  );
}

function ResultRow({ workspaceId, item }: { workspaceId: string; item: SearchItem }) {
  const label = PROVIDERS.find((p) => p.key === item.provider)?.label ?? item.provider;
  return (
    <article className="search-row">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <span className="badge badge-primary flex-shrink-0">{label}</span>
          <strong className="truncate text-[15px]">{item.title || label}</strong>
          <span className="flex-shrink-0 text-[11px] font-bold text-[var(--muted)]">
            관련도 {Math.round(item.score * 100)}%
          </span>
        </div>
        <span className="flex-shrink-0 text-xs text-[var(--muted)]">
          {item.authorName && `${item.authorName} · `}
          {item.createdAt && formatDateTime(item.createdAt)}
        </span>
      </div>
      {item.snippet && <div className="text-[13px] text-[var(--muted)]">{item.snippet}</div>}
      {(item.tasks.length > 0 || item.appPath || item.deepLink) && (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          {item.tasks.map((t) => (
            <Link key={t.taskId} to={`/workspaces/${workspaceId}/tasks/${t.taskId}`} className="badge no-underline">
              ☑ {t.title}
            </Link>
          ))}
          <span className="flex-1" />
          {item.appPath && (
            <Link to={`/workspaces/${workspaceId}${item.appPath}`} className="font-bold">
              ToGather에서 열기
            </Link>
          )}
          {item.deepLink && (
            <a href={item.deepLink} target="_blank" rel="noreferrer">
              {label}에서 보기
            </a>
          )}
        </div>
      )}
    </article>
  );
}
