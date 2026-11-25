package com.togather.task.event;

/** 팀 작업이 지워졌다. 같은 트랜잭션 안에서 이 작업을 가리키는 다른 데이터(논의 정리 항목 등)를 정리한다. */
public record TaskDeletedEvent(Long workspaceId, Long taskId) {
}
