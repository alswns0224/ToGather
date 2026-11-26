// 없는 주소로 들어왔을 때 보여 주는 화면 (개발용 기본 오류 화면 대신)
import { Link, useParams } from 'react-router';

export default function NotFoundPage() {
  const { workspaceId } = useParams();
  const home = workspaceId ? `/workspaces/${workspaceId}/home` : '/workspaces';
  return (
    <section className="stack items-center py-16 text-center">
      <div className="text-[40px]" aria-hidden="true">🔍</div>
      <h1 className="m-0 text-[22px] font-bold">페이지를 찾을 수 없어요</h1>
      <p className="m-0 text-sm text-[var(--muted)]">주소가 바뀌었거나 없는 페이지예요.</p>
      <Link className="btn btn-primary" to={home}>
        {workspaceId ? '대시보드로 가기' : '워크스페이스 목록으로'}
      </Link>
    </section>
  );
}
