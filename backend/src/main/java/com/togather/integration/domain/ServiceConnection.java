package com.togather.integration.domain;

import com.togather.common.domain.BaseTimeEntity;
import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * C-00-05. encryptedTokens는 IntegrationService 밖으로 내보내지 않는다.
 */
@Getter
@Entity
@Table(name = "service_connection")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceConnection extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long connectionId;

    private Long workspaceId;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    private String externalAccountId;

    private String encryptedTokens;

    private String scope;

    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    private ConnectionStatus status;

    public ServiceConnection(Long workspaceId, Long userId, Provider provider, String externalAccountId,
                             String encryptedTokens, String scope, LocalDateTime expiresAt) {
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.provider = provider;
        this.externalAccountId = externalAccountId;
        this.encryptedTokens = encryptedTokens;
        this.scope = scope;
        this.expiresAt = expiresAt;
        this.status = ConnectionStatus.TARGET_NOT_SELECTED;
    }

    public void refreshTokens(String encryptedTokens, String scope, LocalDateTime expiresAt) {
        this.encryptedTokens = encryptedTokens;
        this.scope = scope;
        this.expiresAt = expiresAt;
    }

    public void changeStatus(ConnectionStatus status) {
        this.status = status;
    }

    public boolean isTokenExpired(LocalDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
