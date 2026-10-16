package com.togather.sync.service;

import com.togather.common.enums.Provider;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 서비스별 증분 동기화 어댑터 (B-03). 구현체를 @Component로 등록하면 SyncService가 자동으로 사용한다.
 *
 * <ul>
 *   <li>팀 서비스(Slack·Notion·Drive): 연동 대상(채널·DB·폴더)마다 동기화한다. ownerUserId는 null</li>
 *   <li>개인 서비스(Gmail 등): 사용자마다 동기화한다. targetId는 ""</li>
 * </ul>
 *
 * 외부 API가 실패하면 예외를 그대로 던진다. 이미 저장된 데이터는 지우지 않고, 실패는 SyncService가 기록한다.
 */
public interface SyncAdapter {

    Provider provider();

    /** 워크스페이스에서 동기화할 대상. 팀 서비스는 보통 IntegrationService.getConnectionTargets */
    List<SyncTarget> findTargets(Long workspaceId, Long requestedBy);

    /**
     * since 이후 바뀐 콘텐츠를 가져와 ContentRefService로 저장한다.
     *
     * @return 저장·갱신한 개수와, 다음 동기화의 기준이 될 시각
     */
    SyncResult sync(SyncContext context);

    /** @param ownerUserId 개인 서비스면 소유자, 팀 서비스면 null */
    record SyncTarget(String targetId, Long ownerUserId) {

        public SyncTarget {
            targetId = targetId == null ? "" : targetId;
        }

        public static SyncTarget team(String targetId) {
            return new SyncTarget(targetId, null);
        }

        public static SyncTarget personal(Long ownerUserId) {
            return new SyncTarget("", ownerUserId);
        }
    }

    /**
     * @param since       마지막으로 성공한 동기화 기준 시각. null이면 처음(전체) 동기화
     * @param requestedBy 사용자가 직접 요청했으면 그 사용자, 스케줄러면 null
     */
    /**
     * @param syncCursor 서비스가 주는 동기화 위치 (예: Gmail historyId). 마지막 성공 때 SyncResult로 돌려준 값이다.
     *                   시각(since)으로 이어받는 서비스는 쓰지 않는다. 처음이면 null
     */
    record SyncContext(Long workspaceId, String targetId, Long ownerUserId, LocalDateTime since, String syncCursor,
                       Long requestedBy) {

        /** cursor를 쓰지 않는 어댑터용 (기존 코드 호환) */
        public SyncContext(Long workspaceId, String targetId, Long ownerUserId, LocalDateTime since,
                           Long requestedBy) {
            this(workspaceId, targetId, ownerUserId, since, null, requestedBy);
        }
    }

    /**
     * @param syncCursor 다음 동기화에 넘겨줄 위치. 성공하면 그대로 저장한다. (cursor를 쓰지 않으면 null)
     */
    record SyncResult(int savedCount, LocalDateTime syncedUntil, String syncCursor) {

        /** cursor를 쓰지 않는 어댑터용 (기존 코드 호환) */
        public SyncResult(int savedCount, LocalDateTime syncedUntil) {
            this(savedCount, syncedUntil, null);
        }
    }
}
