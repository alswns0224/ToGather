package com.togather.workspace.dto;

/** 초대 링크로 들어온 사용자에게 보여줄 워크스페이스 정보 */
public record InviteInfoResponse(
        Long workspaceId,
        String name,
        String description,
        long memberCount,
        boolean alreadyMember
) {
}
