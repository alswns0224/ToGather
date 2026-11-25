-- Drive 파일 소유자를 팀장 한 명으로 모으고, 휴지통은 팀장 Drive의 휴지통 폴더로 옮겨 관리한다.

-- 워크스페이스별 Drive 소유자(팀장)와 휴지통 폴더
CREATE TABLE drive_workspace_setting (
    workspace_id            BIGINT       PRIMARY KEY REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    owner_user_id           BIGINT,
    trash_folder_id         VARCHAR(255),
    pending_ownership_count INT          NOT NULL DEFAULT 0,
    last_consolidated_at    TIMESTAMP,
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP    NOT NULL
);

-- ToGather에서 휴지통 폴더로 옮긴 항목과 원래 위치 (복원할 때 쓴다)
CREATE TABLE drive_trash_entry (
    entry_id         BIGSERIAL    PRIMARY KEY,
    workspace_id     BIGINT       NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    item_id          VARCHAR(255) NOT NULL,
    item_name        VARCHAR(255),
    folder           BOOLEAN      NOT NULL,
    root_folder_id   VARCHAR(255) NOT NULL,
    origin_parent_id VARCHAR(255) NOT NULL,
    trashed_by       BIGINT,
    trashed_at       TIMESTAMP    NOT NULL,
    UNIQUE (workspace_id, item_id)
);

-- 팀장을 넘길 때 이전 팀장 소유 항목을 새 팀장에게 넘기는 작업
CREATE TABLE drive_ownership_job (
    job_id          BIGSERIAL    PRIMARY KEY,
    workspace_id    BIGINT       NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    from_user_id    BIGINT       NOT NULL,
    to_user_id      BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    done_count      INT          NOT NULL DEFAULT 0,
    remaining_count INT          NOT NULL DEFAULT 0,
    failed_count    INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_drive_ownership_job_workspace ON drive_ownership_job (workspace_id, status);
