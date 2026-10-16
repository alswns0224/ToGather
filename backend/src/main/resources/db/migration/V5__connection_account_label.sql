-- 연동 관리 화면에 ID 대신 보여줄 계정 이름 (Slack 팀 이름, Notion 워크스페이스 이름, Google 이메일)
-- 기존 연결은 비어 있고, 다시 연결하거나 재인증하면 채워진다.
ALTER TABLE service_connection ADD COLUMN account_label VARCHAR(255);
