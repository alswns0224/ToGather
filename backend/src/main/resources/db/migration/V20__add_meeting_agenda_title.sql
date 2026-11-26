-- 회의 안건은 기존 팀 작업을 연결하거나, 별도의 논의 제목을 직접 입력한다.
-- 이전 로컬 회의 UI 브랜치에서 이미 적용한 경우에도 기존 안건을 보존한다.
ALTER TABLE meeting_agenda ALTER COLUMN task_id DROP NOT NULL;
ALTER TABLE meeting_agenda ADD COLUMN IF NOT EXISTS title VARCHAR(200);
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'meeting_agenda'::regclass
          AND conname = 'meeting_agenda_content_check'
    ) THEN
        ALTER TABLE meeting_agenda ADD CONSTRAINT meeting_agenda_content_check CHECK (
            (task_id IS NOT NULL AND title IS NULL)
            OR (task_id IS NULL AND title IS NOT NULL AND length(btrim(title)) > 0)
        );
    END IF;
END;
$$;
