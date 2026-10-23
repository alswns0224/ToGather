// UI-12 통합 검색 (담당: C)
import PageHeader from '../../components/PageHeader';
import ComingSoon from '../../components/ComingSoon';

export default function SearchPage() {
  return (
    <section className="space-y-6">
      <PageHeader
        eyebrow="SEMANTIC SEARCH"
        title="통합 검색"
        description="Slack · Notion · Drive에 흩어진 내용을 자연어로 찾습니다."
      />
      <ComingSoon>임베딩 · 문맥 기반 검색을 만들고 있습니다. (C-07, C-08)</ComingSoon>
    </section>
  );
}
