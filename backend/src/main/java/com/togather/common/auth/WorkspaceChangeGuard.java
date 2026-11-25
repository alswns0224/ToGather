package com.togather.common.auth;

import com.togather.common.enums.Provider;

/**
 * 팀원·연동 변경 전후에 다른 파트가 확인하거나 이어서 처리할 일. 거절하려면 BusinessException을 던진다.
 * 같은 트랜잭션에서 호출되므로 외부 API는 부르지 않는다.
 */
public interface WorkspaceChangeGuard {

    default void beforeOwnerTransfer(Long workspaceId, Long fromUserId, Long toUserId) {
    }

    default void afterOwnerTransfer(Long workspaceId, Long fromUserId, Long toUserId) {
    }

    default void beforeMemberRemove(Long workspaceId, Long userId) {
    }

    /** @param userId 해제되는 연결의 주인 */
    default void beforeDisconnect(Long workspaceId, Long userId, Provider provider) {
    }
}
