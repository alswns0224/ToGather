package com.togather.integration.service;

import com.togather.content.access.ContentAccessService;
import com.togather.workspace.event.WorkspaceMemberRemovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 팀원을 내보내면 그 워크스페이스에 남은 그 사람의 것을 지운다. 다시 초대받아 들어와도 처음부터 연동해야 한다.
 *
 * <ul>
 *   <li>연동(토큰): 외부 폐기를 시도하고 연결 기록을 지운다. 그 사람이 고른 팀 연동 대상은 다른 팀원에게 넘긴다.</li>
 *   <li>개인 자료: 그 사람만 보던 Slack DM·Gmail 메일·개인 일정 사본, 개인 동기화 기록</li>
 *   <li>AI 업무 보조 후보: 이 워크스페이스의 메시지에서 찾은 그 사람의 후보</li>
 * </ul>
 * 팀 자료(팀 채널 대화, 팀 작업, 회의, 논의 정리 결과)는 팀의 기록이라 남긴다.
 * 내보내기와 같은 트랜잭션에서 지워서, 하나라도 실패하면 내보내기도 되돌린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberRemovalCleaner {

    private final IntegrationService integrationService;
    private final ContentAccessService contentAccess;
    private final JdbcTemplate jdbc;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMemberRemoved(WorkspaceMemberRemovedEvent event) {
        Long workspaceId = event.workspaceId();
        Long userId = event.userId();

        int connections = integrationService.purgeMemberConnections(workspaceId, userId);

        // 후보는 원본 메시지를 지우기 전에 지운다. (원본이 지워지면 출처가 null이 되어 워크스페이스를 알 수 없다)
        int suggestions = jdbc.update("""
                DELETE FROM suggestion
                 WHERE owner_user_id = ?
                   AND source_content_ref_id IN (SELECT content_ref_id FROM content_ref WHERE workspace_id = ?)
                """, userId, workspaceId);
        int personal = jdbc.update(
                "DELETE FROM content_ref WHERE workspace_id = ? AND owner_user_id = ?", workspaceId, userId);
        int states = jdbc.update(
                "DELETE FROM sync_state WHERE workspace_id = ? AND owner_user_id = ?", workspaceId, userId);

        contentAccess.forget(workspaceId, userId);
        log.info("내보낸 팀원 정리: workspaceId={}, userId={}, connections={}, personalContents={}, suggestions={}, syncStates={}",
                workspaceId, userId, connections, personal, suggestions, states);
    }
}
