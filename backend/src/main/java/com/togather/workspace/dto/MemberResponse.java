package com.togather.workspace.dto;

import com.togather.auth.dto.UserSummary;
import com.togather.common.enums.WorkspaceRole;
import java.time.LocalDateTime;

public record MemberResponse(
        Long userId,
        String name,
        String email,
        String profileImage,
        WorkspaceRole role,
        LocalDateTime joinedAt
) {

    public static MemberResponse of(UserSummary user, WorkspaceRole role, LocalDateTime joinedAt) {
        return new MemberResponse(user.userId(), user.name(), user.email(), user.profileImage(), role, joinedAt);
    }
}
