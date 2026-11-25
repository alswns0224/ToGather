-- Drive 시간은 UTC로 저장하던 것을 다른 서비스와 같은 한국 시간(Asia/Seoul)으로 맞춘다.
-- 이미 UTC로 들어간 값만 9시간 옮긴다.
UPDATE content_ref
   SET last_modified_at = last_modified_at + INTERVAL '9 hours'
 WHERE provider = 'GOOGLE_DRIVE'
   AND last_modified_at IS NOT NULL;

UPDATE drive_trash_entry
   SET trashed_at = trashed_at + INTERVAL '9 hours';

UPDATE drive_workspace_setting
   SET last_consolidated_at = last_consolidated_at + INTERVAL '9 hours'
 WHERE last_consolidated_at IS NOT NULL;

-- 지워진 팀 작업을 가리키는 논의 정리 후속 작업 항목은 "아직 만들지 않음"으로 되돌린다.
UPDATE analysis_item
   SET output_target_id = NULL,
       output_status = NULL
 WHERE item_type = 'FOLLOW_UP'
   AND output_target_id IS NOT NULL
   AND output_target_id NOT IN (SELECT task_id FROM task);
