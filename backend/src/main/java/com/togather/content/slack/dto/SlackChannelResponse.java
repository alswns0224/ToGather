package com.togather.content.slack.dto;

import java.time.LocalDateTime;

/**
 * UI-04 좌측 채널 목록. 동기화에 실패해도 저장된 메시지는 보여주고 실패만 안내한다.
 *
 * @param lastError 실패 사유 코드 (예: REAUTH_REQUIRED)
 */
public record SlackChannelResponse(String channelId, String name, LocalDateTime lastSyncedAt, boolean syncFailed,
                                   String lastError) {
}
