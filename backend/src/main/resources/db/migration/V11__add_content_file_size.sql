ALTER TABLE content_ref ADD COLUMN file_size BIGINT;
ALTER TABLE content_ref ADD CONSTRAINT chk_content_file_size_nonnegative
    CHECK (file_size IS NULL OR file_size >= 0);

-- 기존 Drive 파일 크기도 다음 동기화에서 한 번 전체 조회한다. 다른 제공자의 커서는 유지한다.
UPDATE sync_state SET sync_cursor = NULL WHERE provider = 'GOOGLE_DRIVE';
