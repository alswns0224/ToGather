-- 회의 안건은 기존 팀 작업을 연결하거나, 별도의 논의 제목을 직접 입력한다.
ALTER TABLE meeting_agenda ALTER COLUMN task_id DROP NOT NULL;
ALTER TABLE meeting_agenda ADD COLUMN title VARCHAR(200);
ALTER TABLE meeting_agenda ADD CONSTRAINT meeting_agenda_content_check CHECK (
    (task_id IS NOT NULL AND title IS NULL)
    OR (task_id IS NULL AND title IS NOT NULL AND length(btrim(title)) > 0)
);
