package com.togather.sync.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적 증분 동기화 (B-03). 처음 동기화는 사용자가 화면에 들어오거나 직접 요청할 때 시작되고,
 * 그 뒤로는 이 스케줄러가 이어서 동기화한다. 간격은 togather.sync.interval (기본 5분)
 */
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final SyncService syncService;

    @Scheduled(initialDelayString = "${togather.sync.initial-delay:PT1M}",
            fixedDelayString = "${togather.sync.interval:PT5M}")
    public void syncAll() {
        syncService.syncAllKnown();
    }
}
