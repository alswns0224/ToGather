// UI-12 통합 검색 (담당: B, C-07·08 이관)
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { search, type SearchItem, type SearchProvider, type SearchResponse } from './api';
import { ApiError } from '../../api/client';
import PageHeader from '../../components/PageHeader';
import ServiceLogo, { PROVIDER_LOGO } from '../../components/ServiceLogo';
import { Empty, ErrorBox, Loading } from '../../components/Status';
import { BTN_AI, CARD, INPUT, MUTED, SUB_CARD } from '../../components/ui';
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

  const toggle = (key: SearchProvider) =>
    update(query, providers.includes(key) ? providers.filter((p) => p !== key) : [...providers, key]);

  return (
    <section className="space-y-6">
      <PageHeader
        eyebrow="SEMANTIC SEARCH"
        title="통합 검색"
        description="Slack · Notion · Drive에 흩어진 내용을 키워드가 아니라 문맥으로 찾습니다. (BGE-M3 임베딩 · Chroma)"
      />

      <form onSubmit={submit} className={`${CARD} space-y-4 p-6`}>
        <div className="flex gap-2">
          <input
            className={INPUT}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="예: 발표 담당은 누구로 정했지?"
            maxLength={200}
            autoFocus
          />
          <button type="submit" className={`${BTN_AI} flex-shrink-0`} disabled={!input.trim()}>
            🔍 검색
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className={`text-xs ${MUTED}`}>출처</span>
          <button
            type="button"
            onClick={() => update(query, [])}
            className={chip(providers.length === 0)}
          >
            전체
          </button>
          {PROVIDERS.map((p) => (
            <button key={p.key} type="button" onClick={() => toggle(p.key)} className={chip(providers.includes(p.key))}>
              <span className="inline-block h-4 w-4">
                <ServiceLogo service={PROVIDER_LOGO[p.key]} />
              </span>
              {p.label}
            </button>
          ))}
        </div>
      </form>

      {!query.trim() ? (
        <div className={`${CARD} space-y-3 p-6`}>
          <p className="m-0 text-sm font-bold">이렇게 물어보세요</p>
          <div className="flex flex-wrap gap-2">
            {EXAMPLES.map((ex) => (
              <button key={ex} type="button" className={chip(false)} onClick={() => update(ex, providers)}>
                {ex}
              </button>
            ))}
          </div>
          <p className={`m-0 text-xs ${MUTED}`}>
            단어가 정확히 같지 않아도 뜻이 비슷한 대화 · 문서 · 파일을 찾아 관련도순으로 보여줍니다.
          </p>
        </div>
      ) : state.loading ? (
        <Loading text="검색하는 중..." />
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
    <div className="space-y-3">
      <p className={`m-0 text-xs ${MUTED}`}>
        "{data.query}" 검색 결과 {data.items.length}건
        {data.pending > 0 && ` · 아직 검색에 반영 중인 콘텐츠 ${data.pending}건`}
      </p>
      {data.items.length === 0 ? (
        <Empty>
          관련된 내용을 찾지 못했습니다. 다른 표현으로 물어보거나 출처 필터를 풀어 보세요.
          {data.pending > 0 && ' 최근 내용은 1~2분 뒤 검색됩니다.'}
        </Empty>
      ) : (
        data.items.map((item) => <ResultCard key={item.contentRefId} workspaceId={workspaceId} item={item} />)
      )}
    </div>
  );
}

function ResultCard({ workspaceId, item }: { workspaceId: string; item: SearchItem }) {
  const label = PROVIDERS.find((p) => p.key === item.provider)?.label ?? item.provider;
  return (
    <article className={`${CARD} space-y-2 p-5`}>
      <div className="flex items-center gap-2">
        <span className="inline-block h-6 w-6 flex-shrink-0">
          <ServiceLogo service={PROVIDER_LOGO[item.provider]} />
        </span>
        <strong className="min-w-0 truncate text-sm">{item.title || label}</strong>
        <span className="rounded-full bg-indigo-500/15 px-2.5 py-0.5 text-[11px] font-bold text-indigo-500">
          관련도 {Math.round(item.score * 100)}%
        </span>
        <span className="flex-1" />
        <span className={`flex-shrink-0 text-[11px] ${MUTED}`}>
          {item.authorName && `${item.authorName} · `}
          {item.createdAt && formatDateTime(item.createdAt)}
        </span>
      </div>
      {item.snippet && <p className="m-0 text-sm leading-relaxed">{item.snippet}</p>}
      <div className="flex flex-wrap items-center gap-2">
        {item.tasks.map((t) => (
          <Link
            key={t.taskId}
            to={`/workspaces/${workspaceId}/tasks/${t.taskId}`}
            className={`${SUB_CARD} px-2.5 py-1 text-[11px] font-bold no-underline`}
          >
            ✅ {t.title}
          </Link>
        ))}
        <span className="flex-1" />
        {item.appPath && (
          <Link to={`/workspaces/${workspaceId}${item.appPath}`} className="text-xs font-bold">
            ToGather에서 열기
          </Link>
        )}
        {item.deepLink && (
          <a href={item.deepLink} target="_blank" rel="noreferrer" className="text-xs">
            {label}에서 보기
          </a>
        )}
      </div>
    </article>
  );
}

function chip(active: boolean) {
  return `inline-flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-bold ${
    active
      ? 'border-indigo-500 bg-indigo-600 text-white'
      : 'border-slate-200/70 bg-slate-50/90 text-slate-700 hover:border-indigo-400 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-200'
  }`;
}
