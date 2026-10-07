package com.togather.workspace.domain;

import com.togather.common.domain.BaseTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** C-00-04 */
@Getter
@Entity
@Table(name = "invite_link")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteLink extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long inviteLinkId;

    private String token;

    private Long workspaceId;

    private LocalDateTime expiresAt;

    public InviteLink(String token, Long workspaceId, LocalDateTime expiresAt) {
        this.token = token;
        this.workspaceId = workspaceId;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
