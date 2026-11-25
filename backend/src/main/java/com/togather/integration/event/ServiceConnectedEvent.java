package com.togather.integration.event;

import com.togather.common.enums.Provider;

/** 외부 서비스 연결(재인증 포함)을 마쳤다. 커밋 후에 처리한다. */
public record ServiceConnectedEvent(Long workspaceId, Long userId, Provider provider) {
}
