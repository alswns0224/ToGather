-- C-07 콘텐츠 임베딩: Chroma에 넣은 콘텐츠와 넣을 때의 원본 수정 시각.
-- 원본 수정 시각이 바뀌었거나 원본이 삭제되면 다시 임베딩하거나 Chroma에서 지운다.
CREATE TABLE content_embedding (
    content_ref_id     BIGINT PRIMARY KEY REFERENCES content_ref (content_ref_id) ON DELETE CASCADE,
    workspace_id       BIGINT    NOT NULL,
    source_modified_at TIMESTAMP,
    embedded_at        TIMESTAMP NOT NULL
);
