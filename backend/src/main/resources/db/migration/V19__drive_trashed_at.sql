-- Google Drive 휴지통에 들어간 것을 ToGather가 처음 본 시각. (Drive는 휴지통에 넣은 시각을 알려 주지 않는다)
-- 이미 휴지통에 있는 파일은 알 수 없으므로 비워 두고, 화면은 그때 파일 수정 시각을 대신 보여 준다.
ALTER TABLE content_ref ADD COLUMN drive_trashed_at TIMESTAMP;
