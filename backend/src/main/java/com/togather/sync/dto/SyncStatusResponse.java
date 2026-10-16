package com.togather.sync.dto;

import com.togather.common.enums.Provider;
import com.togather.sync.domain.SyncState;
import java.time.LocalDateTime;

/**
 * 대상별 동기화 상태. 화면에 "마지막 동기화 시각"과 실패 안내를 표시한다.
 *
 * @param failed    마지막 시도가 실패했는지 (실패해도 이전에 저장한 콘텐츠는 그대로 보여준다)
 * @param lastError 실패 사유 코드 (예: REAUTH_REQUIRED, EXTERNAL_API_ERROR)
 */
public record SyncStatusResponse(Provider provider, String targetId, LocalDateTime lastSyncedAt,
                                 LocalDateTime lastAttemptAt, boolean failed, String lastError) {

    public static SyncStatusResponse from(SyncState state) {
        return new SyncStatusResponse(state.getProvider(), state.getTargetId(), state.getLastSyncedAt(),
                state.getLastAttemptAt(), state.getFailureCount() > 0, state.getLastError());
    }
}
