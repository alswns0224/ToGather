package com.togather.workspace.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.response.ApiResponse;
import com.togather.workspace.dto.InviteInfoResponse;
import com.togather.workspace.dto.InviteLinkResponse;
import com.togather.workspace.service.InviteLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** C-01-03 */
@RestController
@RequiredArgsConstructor
public class InviteLinkController {

    private final InviteLinkService inviteLinkService;

    @PostMapping("/api/workspaces/{workspaceId}/invite-links")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InviteLinkResponse> createInviteLink(@LoginUser Long userId, @PathVariable Long workspaceId) {
        return ApiResponse.ok(inviteLinkService.createInviteLink(workspaceId, userId));
    }

    /** 유효한 링크가 없으면 data가 null */
    @GetMapping("/api/workspaces/{workspaceId}/invite-links/active")
    public ApiResponse<InviteLinkResponse> getActiveInviteLink(@LoginUser Long userId, @PathVariable Long workspaceId) {
        return ApiResponse.ok(inviteLinkService.getActiveInviteLink(workspaceId, userId));
    }

    @GetMapping("/api/invites/{token}")
    public ApiResponse<InviteInfoResponse> getInviteInfo(@LoginUser Long userId, @PathVariable String token) {
        return ApiResponse.ok(inviteLinkService.getInviteInfo(token, userId));
    }

    /** 참여한 워크스페이스 ID를 반환한다. */
    @PostMapping("/api/invites/{token}/join")
    public ApiResponse<JoinResponse> joinByInviteLink(@LoginUser Long userId, @PathVariable String token) {
        return ApiResponse.ok(new JoinResponse(inviteLinkService.joinByInviteLink(token, userId)));
    }

    public record JoinResponse(Long workspaceId) {
    }
}
