-- V15 이전에 저장된 Drive 파일의 생성 시각(created_at)은 UTC로 남아 있다.
-- 어느 행이 예전 값인지 시각만으로는 가릴 수 없으므로, Drive 동기화 위치를 비워 다음 동기화를 전체 동기화로 돌린다.
-- 전체 동기화는 폴더의 모든 파일을 다시 저장하면서 생성 시각을 Google이 준 한국 시간 값으로 바로잡는다.
UPDATE sync_state
   SET sync_cursor = NULL
 WHERE provider = 'GOOGLE_DRIVE';
