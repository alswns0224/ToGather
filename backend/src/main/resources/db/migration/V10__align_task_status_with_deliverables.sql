-- 이전 버전에서 산출물 제출 여부와 다르게 수동 지정된 상태를 바로잡는다.
-- 산출물이 없는 Task는 사용자가 지정한 상태를 유지한다.
WITH expected_status AS (
    SELECT task_id,
           CASE WHEN bool_and(is_submitted) THEN 'DONE' ELSE 'IN_PROGRESS' END AS status
    FROM task_deliverable
    GROUP BY task_id
)
UPDATE task t
SET status = expected.status
FROM expected_status expected
WHERE t.task_id = expected.task_id
  AND t.status <> expected.status;
