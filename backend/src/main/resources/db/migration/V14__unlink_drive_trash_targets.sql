-- ToGather 휴지통 폴더는 진짜 휴지통처럼 쓰므로 연동할 Drive 폴더로 고를 수 없다. 이미 고른 것은 연동에서 뺀다.
DELETE FROM integration_target
 WHERE external_target_id IN (
       SELECT trash_folder_id FROM drive_workspace_setting WHERE trash_folder_id IS NOT NULL
 );
