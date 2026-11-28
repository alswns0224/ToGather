-- 대화에 나온 회의·일정 길이("2시간 정도"). 비어 있으면 1시간으로 본다.
ALTER TABLE analysis_item ADD COLUMN suggested_duration_minutes INT;
ALTER TABLE suggestion ADD COLUMN duration_minutes INT;
