package com.togather.content.drive.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 팀장 넘기기 인계 작업과, 팀장 소유가 아닌 항목 정리를 주기적으로 한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriveOwnershipScheduler {

    private final DriveOwnershipService ownershipService;
    private final JdbcTemplate jdbc;

    @Scheduled(initialDelayString = "${togather.drive.ownership.job-initial-delay:PT1M}",
        fixedDelayString = "${togather.drive.ownership.job-interval:PT1M}")
    public void processTransfers() {
        ownershipService.processRunningJobs();
    }

    @Scheduled(initialDelayString = "${togather.drive.ownership.consolidate-initial-delay:PT2M}",
        fixedDelayString = "${togather.drive.ownership.consolidate-interval:PT10M}")
    public void consolidateAll() {
        for (Long workspaceId : jdbc.queryForList("""
            SELECT DISTINCT c.workspace_id
              FROM service_connection c
              JOIN integration_target t ON t.connection_id = c.connection_id
             WHERE c.provider = 'GOOGLE_DRIVE' AND c.status <> 'DISCONNECTED'
            """, Long.class)) {
            try {
                ownershipService.consolidate(workspaceId);
            } catch (RuntimeException e) {
                log.warn("Drive 소유권 정리 실패: workspaceId={}, error={}", workspaceId, e.getMessage());
            }
        }
    }
}
