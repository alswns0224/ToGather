// UI-06 파일 (Google Drive) (담당: C)
import PageHeader from '../../components/PageHeader';
import ComingSoon from '../../components/ComingSoon';

export default function DrivePage() {
  return (
    <section className="space-y-6">
      <PageHeader eyebrow="GOOGLE DRIVE" title="자료 (Drive)" description="연동한 폴더의 파일과 Task 산출물을 관리합니다." />
      <ComingSoon>파일 목록 · 업로드 · 미리보기를 만들고 있습니다. (C-03)</ComingSoon>
    </section>
  );
}
