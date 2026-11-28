-- 마감에 시각을 함께 둔다. 비어 있으면 그날 하루 종일(23:59까지)로 본다.
ALTER TABLE task ADD COLUMN due_time TIME;
ALTER TABLE analysis_item ADD COLUMN suggested_due_time TIME;
ALTER TABLE personal_task ADD COLUMN due_time TIME;
ALTER TABLE suggestion ADD COLUMN due_time TIME;
