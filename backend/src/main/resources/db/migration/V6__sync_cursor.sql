-- 증분 동기화 위치를 문자열로 저장한다. (Gmail historyId 등, C-05 요청)
-- 시각(last_synced_at)으로 이어받는 Slack·Notion·Drive는 쓰지 않아 NULL로 둔다.
ALTER TABLE sync_state ADD COLUMN sync_cursor VARCHAR(255);
