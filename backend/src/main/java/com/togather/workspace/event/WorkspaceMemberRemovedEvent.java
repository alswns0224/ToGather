package com.togather.workspace.event;

/**
 * 팀원을 워크스페이스에서 내보냈다. 그 사람의 연동·개인 자료는 이 이벤트를 받은 쪽에서 지운다.
 * (내보낸 사람이 초대 링크로 다시 들어와도 예전 연동이 남아 있지 않게)
 */
public record WorkspaceMemberRemovedEvent(Long workspaceId, Long userId) {
}
