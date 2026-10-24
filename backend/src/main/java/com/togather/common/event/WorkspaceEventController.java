package com.togather.common.event;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 화면이 바뀐 내용을 실시간으로 받는 연결 (EventSource) */
@RestController
@RequiredArgsConstructor
public class WorkspaceEventController {

    private final WorkspaceEventService workspaceEventService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    @GetMapping(path = "/api/workspaces/{workspaceId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@LoginUser Long userId, @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return workspaceEventService.subscribe(workspaceId);
    }
}
