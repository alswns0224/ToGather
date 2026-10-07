package com.togather.workspace.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.WorkspaceRole;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-03. 대리키 memberId는 구현 편의를 위해 추가 (workspace_id + user_id는 UNIQUE) */
@Getter
@Entity
@Table(name = "workspace_member")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkspaceMember extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long memberId;

    private Long workspaceId;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private WorkspaceRole role;

    public WorkspaceMember(Long workspaceId, Long userId, WorkspaceRole role) {
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.role = role;
    }

    public boolean isOwner() {
        return role == WorkspaceRole.OWNER;
    }
}
