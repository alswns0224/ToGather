-- 연동 대상(채널·데이터베이스·폴더·캘린더)의 표시 이름. 연동 관리 화면에 보여준다. (UI-03)
ALTER TABLE integration_target ADD COLUMN name VARCHAR(255);

-- 한 사용자는 워크스페이스·서비스마다 활성 연결을 하나만 가진다. (해제된 연결은 기록으로 남김)
CREATE UNIQUE INDEX uq_service_connection_active
    ON service_connection (workspace_id, user_id, provider)
    WHERE status <> 'DISCONNECTED';
