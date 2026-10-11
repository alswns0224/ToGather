package com.togather.analysis.service;

import com.togather.common.enums.Provider;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.sync.domain.SyncState;
import com.togather.sync.service.SyncStateService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * C-04-02 AnalysisScheduler. Slack을 동기화하는 워크스페이스에서, 마지막 확인 이후 갱신되고
 * 논의가 끝난 스레드를 자동으로 분석해 검토 대기 목록에 올린다. (확정은 사용자가 한다)
 * Gemini 호출을 줄이려고 워크스페이스마다 한 번에 최대 5개 스레드만 분석한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisScheduler {

    static final int MAX_THREADS_PER_RUN = 5;
    /** 서버를 켠 직후에는 최근 하루치만 본다. */
    private static final Duration FIRST_LOOKBACK = Duration.ofDays(1);

    private final AnalysisService analysisService;
    private final SyncStateService syncStateService;
    private final Map<Long, LocalDateTime> lastChecked = new ConcurrentHashMap<>();

    @Scheduled(initialDelayString = "${togather.analysis.initial-delay:PT3M}",
            fixedDelayString = "${togather.analysis.interval:PT10M}")
    public void run() {
        Set<Long> workspaceIds = syncStateService.getAllStates().stream()
                .filter(s -> s.getProvider() == Provider.SLACK && s.getLastSyncedAt() != null)
                .map(SyncState::getWorkspaceId)
                .collect(Collectors.toSet());
        for (Long workspaceId : workspaceIds) {
            LocalDateTime now = LocalDateTime.now(SlackApiClient.ZONE);
            // 논의가 끝났는지 판단하는 시간만큼 겹쳐서 본다. (그 사이 조용해진 스레드를 놓치지 않도록)
            LocalDateTime since = lastChecked.getOrDefault(workspaceId, now.minus(FIRST_LOOKBACK))
                    .minus(AnalysisService.SETTLE_TIME);
            try {
                int count = analysisService.collectAndAnalyze(workspaceId, since, MAX_THREADS_PER_RUN);
                if (count > 0) {
                    log.info("자동 논의 분석: workspaceId={}, threads={}", workspaceId, count);
                }
                // 분석 한도에 걸렸으면 다음 번에 같은 범위를 다시 본다.
                if (count < MAX_THREADS_PER_RUN) {
                    lastChecked.put(workspaceId, now);
                }
            } catch (RuntimeException e) {
                log.warn("자동 논의 분석 실패: workspaceId={}, error={}", workspaceId, e.getClass().getSimpleName());
            }
        }
    }
}
