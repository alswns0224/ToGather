-- 팀 캘린더는 더 이상 고르지 않는다. 팀 일정은 늘 팀장의 기본 캘린더에 들어간다. (Gmail처럼 연결만 한다)
-- 예전에 고른 팀 캘린더 선택은 쓰지 않으므로 지운다. 이미 등록한 팀 일정은 캘린더 주인(이메일)의 연결로 계속 찾는다.
DELETE FROM integration_target
WHERE connection_id IN (SELECT connection_id FROM service_connection WHERE provider = 'GOOGLE_CALENDAR');
