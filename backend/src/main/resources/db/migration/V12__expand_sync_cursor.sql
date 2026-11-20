-- Slack DM은 페이지 커서와 수집 구간을 함께 보관하며 커서 길이를 임의로 자르지 않는다.
ALTER TABLE sync_state ALTER COLUMN sync_cursor TYPE TEXT;
