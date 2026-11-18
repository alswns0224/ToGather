package com.togather.workspace.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.WorkspaceRole;
import com.togather.common.response.ApiResponse;
import com.togather.workspace.dto.MemberResponse;
import com.togather.workspace.service.WorkspaceMemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** 관리자 임명(ADMIN) · 해제(MEMBER). 바뀐 팀원 목록을 반환한다. */
    @PatchMapping("/{targetUserId}/role")
    public ApiResponse<List<MemberResponse>> changeRole(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                        @PathVariable Long targetUserId,
                                                        @Valid @RequestBody RoleChangeRequest request) {
        return ApiResponse.ok(workspaceMemberService.changeRole(workspaceId, userId, targetUserId, request.role()));
    }

    public record RoleChangeRequest(@NotNull WorkspaceRole role) {
    }

    /** 팀장 넘기기: 대상은 팀장이 되고 요청한 사람은 팀원이 된다. 바뀐 팀원 목록을 반환한다. */
    @PostMapping("/{targetUserId}/transfer-owner")
    public ApiResponse<List<MemberResponse>> transferOwner(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                           @PathVariable Long targetUserId) {
        return ApiResponse.ok(workspaceMemberService.transferOwner(workspaceId, userId, targetUserId));
    }

    /** 내보낸 뒤의 팀원 목록을 반환한다. */
    @DeleteMapping("/{targetUserId}")
    public ApiResponse<List<MemberResponse>> removeMember(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                          @PathVariable Long targetUserId) {
        return ApiResponse.ok(workspaceMemberService.removeMember(workspaceId, userId, targetUserId));
    }
}
