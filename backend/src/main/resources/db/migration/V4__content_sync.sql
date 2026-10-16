-- =========================================================
-- B. 증분 동기화와 콘텐츠 작성자 (B-0)
-- =========================================================

-- 외부 원본의 최종 수정 시각, 마지막 동기화 실패 여부(UI-04·05·06), 작성자(UI-04 작성자 표시, UC-13 본인 관련 메시지)
ALTER TABLE content_ref ADD COLUMN last_modified_at TIMESTAMP;
ALTER TABLE content_ref ADD COLUMN sync_failed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE content_ref ADD COLUMN author_external_id VARCHAR(255);
ALTER TABLE content_ref ADD COLUMN author_name VARCHAR(255);

-- 채널(폴더·DB)별 시간순 목록 조회
CREATE INDEX idx_content_ref_channel ON content_ref (workspace_id, provider, channel_id, created_at);

-- 서비스·연동 대상별 마지막 동기화 시각. 개인 서비스(Gmail 등)는 owner_user_id로 사용자마다 따로 둔다.
CREATE TABLE sync_state (
    sync_state_id   BIGSERIAL PRIMARY KEY,
    workspace_id    BIGINT       NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    provider        VARCHAR(30)  NOT NULL,
    target_id       VARCHAR(255) NOT NULL DEFAULT '', -- 대상이 없는 서비스는 ''
    owner_user_id   BIGINT REFERENCES users (user_id) ON DELETE CASCADE,
    last_synced_at  TIMESTAMP, -- 다음 증분 동기화의 기준 시각 (마지막 성공 시 갱신)
    last_attempt_at TIMESTAMP,
    failure_count   INT          NOT NULL DEFAULT 0, -- 연속 실패 횟수 (성공하면 0)
    last_error      VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX uq_sync_state ON sync_state (workspace_id, provider, target_id, COALESCE(owner_user_id, 0));
