-- 팀 일정(회의 확정 등)을 참여자 각자의 캘린더에 넣은 사본. 수정·취소할 때 사본도 함께 고치거나 지운다.
CREATE TABLE calendar_event_copy (
    copy_id        BIGSERIAL    PRIMARY KEY,
    content_ref_id BIGINT       NOT NULL REFERENCES content_ref (content_ref_id) ON DELETE CASCADE,
    user_id        BIGINT       NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    calendar_id    VARCHAR(255) NOT NULL,
    event_id       VARCHAR(255) NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    UNIQUE (content_ref_id, user_id)
);
