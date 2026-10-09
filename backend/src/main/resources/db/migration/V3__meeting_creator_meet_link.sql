-- 회의 수정·취소·확정 권한(만든 사람 또는 팀장) 확인과 확정 후 화상 회의 링크 표시(UI-10)
ALTER TABLE meeting ADD COLUMN created_by BIGINT REFERENCES users (user_id);
ALTER TABLE meeting ADD COLUMN meet_link TEXT;

CREATE INDEX idx_meeting_agenda_task ON meeting_agenda (task_id);
CREATE INDEX idx_meeting_participant_user ON meeting_participant (user_id);
