package com.togather.workspace.dto;

import com.togather.common.enums.WorkspaceRole;
import com.togather.workspace.domain.Workspace;
import java.time.LocalDateTime;

public record WorkspaceResponse(
        Long workspaceId,
        String name,
        String description,
        long memberCount,
        WorkspaceRole myRole,
        LocalDateTime createdAt
) {

    public static WorkspaceResponse of(Workspace workspace, long memberCount, WorkspaceRole myRole) {
        return new WorkspaceResponse(workspace.getWorkspaceId(), workspace.getName(), workspace.getDescription(),
                memberCount, myRole, workspace.getCreatedAt());
    }
}
