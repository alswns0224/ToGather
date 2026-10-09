package com.togather.workspace.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.response.ApiResponse;
import com.togather.workspace.dto.MemberResponse;
import com.togather.workspace.service.WorkspaceMemberService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C-01-04 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/members")
public class WorkspaceMemberController {

    private final WorkspaceMemberService workspaceMemberService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    @GetMapping
    public ApiResponse<List<MemberResponse>> getMembers(@LoginUser Long userId, @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(workspaceMemberService.getMembers(workspaceId));
    }

    /** 내보낸 뒤의 팀원 목록을 반환한다. */
    @DeleteMapping("/{targetUserId}")
    public ApiResponse<List<MemberResponse>> removeMember(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                          @PathVariable Long targetUserId) {
        return ApiResponse.ok(workspaceMemberService.removeMember(workspaceId, userId, targetUserId));
    }
}
