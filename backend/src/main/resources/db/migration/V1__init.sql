-- ToGather 초기 스키마 (설계명세서 v1.0 CD-00 도메인 모델 기준)
-- 스키마 변경은 새 버전 파일(V2__xxx.sql ...)을 추가해서만 한다. 이미 적용된 파일은 수정하지 않는다.

-- =========================================================
-- DB-01 사용자 · 워크스페이스 · 구성원 · 초대 링크
-- =========================================================
CREATE TABLE users (
    user_id       BIGSERIAL PRIMARY KEY,
    google_sub    VARCHAR(255) NOT NULL UNIQUE,
    email         VARCHAR(320) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    profile_image TEXT,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

CREATE TABLE workspace (
    workspace_id BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    description  TEXT,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE TABLE workspace_member (
    member_id    BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    user_id      BIGINT      NOT NULL REFERENCES users (user_id),
    role         VARCHAR(20) NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    updated_at   TIMESTAMP   NOT NULL,
    UNIQUE (workspace_id, user_id)
);
CREATE INDEX idx_workspace_member_user ON workspace_member (user_id);

CREATE TABLE invite_link (
    invite_link_id BIGSERIAL PRIMARY KEY,
    token          VARCHAR(64) NOT NULL UNIQUE,
    workspace_id   BIGINT      NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    expires_at     TIMESTAMP,
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP   NOT NULL
);

-- =========================================================
-- DB-02 외부 서비스 연동
-- =========================================================
CREATE TABLE service_connection (
    connection_id       BIGSERIAL PRIMARY KEY,
    workspace_id        BIGINT      NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    user_id             BIGINT      NOT NULL REFERENCES users (user_id),
    provider            VARCHAR(30) NOT NULL,
    external_account_id VARCHAR(255),
    encrypted_tokens    TEXT        NOT NULL,
    scope               TEXT,
    expires_at          TIMESTAMP,
    status              VARCHAR(30) NOT NULL,
    created_at          TIMESTAMP   NOT NULL,
    updated_at          TIMESTAMP   NOT NULL
);
CREATE INDEX idx_service_connection_ws_provider ON service_connection (workspace_id, provider);

CREATE TABLE integration_target (
    target_id          BIGSERIAL PRIMARY KEY,
    connection_id      BIGINT       NOT NULL REFERENCES service_connection (connection_id) ON DELETE CASCADE,
    external_target_id VARCHAR(255) NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    UNIQUE (connection_id, external_target_id)
);

-- =========================================================
-- DB-03 외부 콘텐츠 공통 참조
-- =========================================================
CREATE TABLE content_ref (
    content_ref_id    BIGSERIAL PRIMARY KEY,
    workspace_id      BIGINT       NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    owner_user_id     BIGINT REFERENCES users (user_id), -- 개인 콘텐츠(Gmail, Calendar) 소유자
    provider          VARCHAR(30)  NOT NULL,
    content_type      VARCHAR(20)  NOT NULL,
    origin_id         VARCHAR(255) NOT NULL,
    channel_id        VARCHAR(255),
    parent_ref_id     BIGINT REFERENCES content_ref (content_ref_id) ON DELETE SET NULL,
    content           TEXT,
    is_origin_deleted BOOLEAN      NOT NULL DEFAULT FALSE,
    snapshot          TEXT,
    title             VARCHAR(500),
    deep_link         TEXT,
    created_at        TIMESTAMP,
    UNIQUE (workspace_id, provider, origin_id)
);
CREATE INDEX idx_content_ref_parent ON content_ref (parent_ref_id);
CREATE INDEX idx_content_ref_owner ON content_ref (owner_user_id);

-- =========================================================
-- DB-04 Slack 논의 분석
-- =========================================================
CREATE TABLE discussion_analysis (
    analysis_id          BIGSERIAL PRIMARY KEY,
    content_ref_id       BIGINT      NOT NULL REFERENCES content_ref (content_ref_id) ON DELETE CASCADE,
    status               VARCHAR(20) NOT NULL,
    requested_by         BIGINT REFERENCES users (user_id), -- null이면 시스템(자동 수집)
    analyzed_until       TIMESTAMP,
    notion_page_ref_id   BIGINT REFERENCES content_ref (content_ref_id) ON DELETE SET NULL,
    notion_output_status VARCHAR(20),
    created_at           TIMESTAMP   NOT NULL,
    updated_at           TIMESTAMP   NOT NULL
);

CREATE TABLE analysis_item (
    item_id                   BIGSERIAL PRIMARY KEY,
    analysis_id               BIGINT      NOT NULL REFERENCES discussion_analysis (analysis_id) ON DELETE CASCADE,
    item_type                 VARCHAR(30) NOT NULL,
    content                   TEXT,
    is_undetermined           BOOLEAN     NOT NULL DEFAULT FALSE,
    output_target_id          BIGINT, -- Task 또는 Meeting ID (item_type에 따라 다름)
    output_status             VARCHAR(20),
    suggested_assignee_id     BIGINT REFERENCES users (user_id),
    suggested_due_date        DATE,
    suggested_datetime        TIMESTAMP,
    suggested_participant_ids BIGINT[],
    created_at                TIMESTAMP   NOT NULL,
    updated_at                TIMESTAMP   NOT NULL
);
CREATE INDEX idx_analysis_item_analysis ON analysis_item (analysis_id);

CREATE TABLE analysis_evidence (
    evidence_id    BIGSERIAL PRIMARY KEY,
    item_id        BIGINT    NOT NULL REFERENCES analysis_item (item_id) ON DELETE CASCADE,
    content_ref_id BIGINT    NOT NULL REFERENCES content_ref (content_ref_id) ON DELETE CASCADE,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL
);

-- =========================================================
-- DB-05 Task · 산출물 · 관련 자료 연결
-- =========================================================
CREATE TABLE task (
    task_id                 BIGSERIAL PRIMARY KEY,
    workspace_id            BIGINT       NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    title                   VARCHAR(200) NOT NULL,
    description             TEXT,
    due_date                DATE,
    status                  VARCHAR(20)  NOT NULL,
    assignee_id             BIGINT REFERENCES users (user_id),
    origin_analysis_item_id BIGINT REFERENCES analysis_item (item_id) ON DELETE SET NULL,
    created_at              TIMESTAMP    NOT NULL,
    updated_at              TIMESTAMP    NOT NULL
);
CREATE INDEX idx_task_workspace ON task (workspace_id);
CREATE INDEX idx_task_assignee ON task (assignee_id);

CREATE TABLE task_deliverable (
    deliverable_id BIGSERIAL PRIMARY KEY,
    task_id        BIGINT       NOT NULL REFERENCES task (task_id) ON DELETE CASCADE,
    name           VARCHAR(200) NOT NULL,
    ext            VARCHAR(20),
    file_id        VARCHAR(255),
    file_name      VARCHAR(500),
    link           TEXT,
    submitted_by   BIGINT REFERENCES users (user_id),
    submitted_at   TIMESTAMP,
    is_submitted   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

CREATE TABLE task_content_link (
    link_id        BIGSERIAL PRIMARY KEY,
    task_id        BIGINT      NOT NULL REFERENCES task (task_id) ON DELETE CASCADE,
    content_ref_id BIGINT      NOT NULL REFERENCES content_ref (content_ref_id) ON DELETE CASCADE,
    link_type      VARCHAR(20) NOT NULL,
    created_at     TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP   NOT NULL,
    UNIQUE (task_id, content_ref_id)
);
CREATE INDEX idx_task_content_link_content ON task_content_link (content_ref_id);

-- =========================================================
-- DB-06 회의 · 참여자 · 안건 · 가용 시간 · 추천 시간
-- =========================================================
CREATE TABLE meeting (
    meeting_id           BIGSERIAL PRIMARY KEY,
    workspace_id         BIGINT       NOT NULL REFERENCES workspace (workspace_id) ON DELETE CASCADE,
    title                VARCHAR(200) NOT NULL,
    duration_minutes     INT          NOT NULL,
    candidate_start_at   TIMESTAMP    NOT NULL,
    candidate_end_at     TIMESTAMP    NOT NULL,
    meeting_mode         VARCHAR(50),
    status               VARCHAR(20)  NOT NULL,
    start_at             TIMESTAMP,
    calendar_ref_id      BIGINT REFERENCES content_ref (content_ref_id) ON DELETE SET NULL,
    calendar_sync_failed BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL
);
CREATE INDEX idx_meeting_workspace ON meeting (workspace_id);

CREATE TABLE meeting_participant (
    participant_id BIGSERIAL PRIMARY KEY,
    meeting_id     BIGINT    NOT NULL REFERENCES meeting (meeting_id) ON DELETE CASCADE,
    user_id        BIGINT    NOT NULL REFERENCES users (user_id),
    has_responded  BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL,
    UNIQUE (meeting_id, user_id)
);

CREATE TABLE meeting_agenda (
    agenda_id  BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT    NOT NULL REFERENCES meeting (meeting_id) ON DELETE CASCADE,
    task_id    BIGINT    NOT NULL REFERENCES task (task_id) ON DELETE CASCADE,
    order_no   INT       NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE availability_slot (
    slot_id    BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT      NOT NULL REFERENCES meeting (meeting_id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (user_id),
    preference VARCHAR(20) NOT NULL,
    source     VARCHAR(20) NOT NULL,
    start_at   TIMESTAMP   NOT NULL,
    end_at     TIMESTAMP   NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);
CREATE INDEX idx_availability_slot_meeting ON availability_slot (meeting_id, user_id);

CREATE TABLE meeting_time_candidate (
    candidate_id        BIGSERIAL PRIMARY KEY,
    meeting_id          BIGINT    NOT NULL REFERENCES meeting (meeting_id) ON DELETE CASCADE,
    start_at            TIMESTAMP NOT NULL,
    rank_no             INT       NOT NULL,
    available_count     INT       NOT NULL,
    non_preferred_count INT       NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);

-- =========================================================
-- DB-07 AI 일정·Task 후보 · 개인 할 일
-- =========================================================
CREATE TABLE personal_task (
    personal_task_id      BIGSERIAL PRIMARY KEY,
    owner_user_id         BIGINT       NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    title                 VARCHAR(200) NOT NULL,
    due_date              DATE,
    source_content_ref_id BIGINT REFERENCES content_ref (content_ref_id) ON DELETE SET NULL,
    status                VARCHAR(20)  NOT NULL,
    created_at            TIMESTAMP    NOT NULL,
    updated_at            TIMESTAMP    NOT NULL
);
CREATE INDEX idx_personal_task_owner ON personal_task (owner_user_id);

CREATE TABLE suggestion (
    suggestion_id            BIGSERIAL PRIMARY KEY,
    owner_user_id            BIGINT       NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    type                     VARCHAR(20)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    source_content_ref_id    BIGINT REFERENCES content_ref (content_ref_id) ON DELETE SET NULL,
    title                    VARCHAR(200) NOT NULL,
    detected_datetime        TIMESTAMP,
    due_date                 DATE,
    created_event_id         BIGINT REFERENCES content_ref (content_ref_id) ON DELETE SET NULL,
    created_personal_task_id BIGINT REFERENCES personal_task (personal_task_id) ON DELETE SET NULL,
    created_at               TIMESTAMP    NOT NULL,
    updated_at               TIMESTAMP    NOT NULL
);
CREATE INDEX idx_suggestion_owner_status ON suggestion (owner_user_id, status);
