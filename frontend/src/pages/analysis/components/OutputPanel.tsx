import type { AnalysisResultDTO } from '../api';

/** 확정된 분석의 실행 연계 (B-08에서 Notion·Task·Calendar 연계를 추가한다) */
export default function OutputPanel({
  analysis,
}: {
  workspaceId: string;
  analysis: AnalysisResultDTO;
  onChanged: () => void;
}) {
  return (
    <div className="alert alert-success">확정되었습니다. ({analysis.items.length}개 항목)</div>
  );
}
