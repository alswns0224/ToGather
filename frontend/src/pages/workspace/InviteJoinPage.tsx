// UI-02 초대 링크로 참여 (담당: A)
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { getInviteInfo, joinByInviteLink } from './api';
import { useAsync } from '../../hooks/useAsync';
import { ErrorBox, Loading } from '../../components/Status';

export default function InviteJoinPage() {
  const { token = '' } = useParams();
  const navigate = useNavigate();
  const { loading, error, data } = useAsync(() => getInviteInfo(token), [token]);
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<Error>();

  const join = async () => {
    setJoining(true);
    setJoinError(undefined);
    try {
      const { workspaceId } = await joinByInviteLink(token);
      navigate(`/workspaces/${workspaceId}`, { replace: true });
    } catch (err) {
      setJoinError(err as Error);
      setJoining(false);
    }
  };

  return (
    <div className="center-page">
      <div className="card login-card">
        <h1 className="logo">ToGather</h1>
        {loading && <Loading />}
        {error && (
          <>
            <ErrorBox error={error} />
            <Link to="/workspaces">워크스페이스 목록으로</Link>
          </>
        )}
        {data && (
          <>
            <p className="muted">워크스페이스에 초대받았습니다</p>
            <h2>{data.name}</h2>
            {data.description && <p>{data.description}</p>}
            <p className="small muted">팀원 {data.memberCount}명</p>
            {joinError && <ErrorBox error={joinError} />}
            {data.alreadyMember ? (
              <Link className="btn btn-primary btn-block" to={`/workspaces/${data.workspaceId}`}>
                이미 참여 중입니다 — 이동하기
              </Link>
            ) : (
              <button className="btn btn-primary btn-block" onClick={join} disabled={joining}>
                {joining ? '참여하는 중...' : '참여하기'}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}
