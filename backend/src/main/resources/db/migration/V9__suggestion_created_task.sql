-- AI 업무 보조: 할 일 후보를 팀 Task로도 승인할 수 있다. 만든 Task를 기록한다.
ALTER TABLE suggestion
    ADD COLUMN created_task_id BIGINT REFERENCES task (task_id) ON DELETE SET NULL;
