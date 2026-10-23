// UI-11 Task 협업 맥락 (담당: C)
import PageHeader from '../../components/PageHeader';
import ComingSoon from '../../components/ComingSoon';

export default function TaskContextPage() {
  return (
    <section className="space-y-6">
      <PageHeader
        eyebrow="TASK CONTEXT"
        title="Task 협업 맥락"
        description="Task에 연결된 대화 · 결정사항 · 문서 · 산출물 · 회의를 한 번에 봅니다."
      />
      <ComingSoon>협업 맥락 화면을 만들고 있습니다. (API는 준비됨: getTaskContext)</ComingSoon>
    </section>
  );
}
