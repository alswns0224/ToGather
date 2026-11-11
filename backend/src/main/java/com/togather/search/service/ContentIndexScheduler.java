package com.togather.search.service;

import com.togather.common.exception.BusinessException;
import com.togather.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 밀린 콘텐츠 임베딩 (C-07). 간격은 togather.search.index-interval (기본 1분)
 * Ollama·Chroma가 꺼져 있으면 이번 차례는 건너뛰고, 로그는 상태가 바뀔 때만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentIndexScheduler {

    /** 한 번 실행할 때 처리하는 최대 묶음 수 (처음 색인할 때 오래 붙잡지 않도록) */
    private static final int MAX_BATCHES = 20;

    private final ContentIndexService contentIndexService;
    private final SearchProperties properties;
    private boolean unavailable;

    @Scheduled(initialDelayString = "${togather.search.initial-delay:PT30S}",
            fixedDelayString = "${togather.search.index-interval:PT1M}")
    public void indexPending() {
        if (!properties.enabled()) {
            return;
        }
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES; i++) {
                int done = contentIndexService.indexPending();
                total += done;
                if (done < properties.batchSize()) {
                    break;
                }
            }
            if (unavailable) {
                log.info("통합 검색 색인을 다시 시작합니다.");
                unavailable = false;
            }
            if (total > 0) {
                log.info("통합 검색 색인: {}건 처리", total);
            }
        } catch (BusinessException e) {
            if (!unavailable) {
                log.warn("통합 검색 색인 건너뜀 (Ollama·Chroma 연결 확인): {}", e.getErrorCode());
                unavailable = true;
            }
        }
    }
}
