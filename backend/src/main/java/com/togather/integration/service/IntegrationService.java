package com.togather.integration.service;

import com.togather.auth.dto.OAuthToken;
import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.OAuthStateStore.OAuthState;
import com.togather.auth.service.OAuthStateStore.Purpose;
import com.togather.auth.service.UserService;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.config.TogatherProperties;
import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.integration.client.OAuthProviderClient;
import com.togather.integration.client.OAuthProviderClient.AuthResult;
import com.togather.integration.client.OAuthProviderClient.TargetOption;
import com.togather.integration.domain.IntegrationTarget;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.dto.ConnectionStatusDTO;
import com.togather.integration.dto.ConnectionStatusDTO.TargetResponse;
import com.togather.integration.dto.IntegrationStatusResponse;
import com.togather.integration.dto.TargetSelectRequest;
import com.togather.integration.repository.IntegrationTargetRepository;
import com.togather.integration.repository.ServiceConnectionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-02-02. 외부 협업 서비스 연동 (UC-03-01 ~ 04)
 *
 * <ul>
 *   <li>팀 서비스(Slack, Notion, Drive): 워크스페이스에서 누군가 연결하면 팀원 모두가 그 연결을 쓴다.</li>
 *   <li>개인 서비스(Gmail, Calendar): 사용자마다 각자 연결한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationService {

    /** 사용자마다 각자 연결하는 서비스 */
    public static final Set<Provider> PERSONAL_PROVIDERS = Set.of(Provider.GMAIL, Provider.GOOGLE_CALENDAR);
    /** 만료 직전 토큰을 미리 갱신하는 여유 시간 */
    private static final long EXPIRY_MARGIN_SECONDS = 60;

    private final ServiceConnectionRepository connectionRepository;
    private final IntegrationTargetRepository targetRepository;
    private final List<OAuthProviderClient> clients;
    private final TokenCipher tokenCipher;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final UserService userService;
    private final TogatherProperties properties;

    // ===================== 연결 (인증) =====================

    /** 인증 화면 주소를 만든다. state는 Controller가 세션에 저장한 값 */
    public String buildConnectUrl(Long workspaceId, Long userId, Provider provider, String state) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        OAuthProviderClient client = clientFor(provider);
        return client.buildAuthorizeUrl(provider, state, redirectUri(client));
    }

    /** 재인증 대상 연결을 확인한다. 연결한 본인만 재인증할 수 있다. */
    public ServiceConnection getConnectionForReauth(Long workspaceId, Long userId, Long connectionId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        ServiceConnection connection = findConnection(workspaceId, connectionId);
        if (!connection.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        return connection;
    }

    public String buildReauthUrl(ServiceConnection connection, String state) {
        OAuthProviderClient client = clientFor(connection.getProvider());
        return client.buildAuthorizeUrl(connection.getProvider(), state, redirectUri(client));
    }

    /**
     * OAuth 콜백 처리. 새 연결이면 저장하고, 재인증이면 같은 계정인지 확인한 뒤 토큰을 바꾼다.
     *
     * @return 처리한 연결 ID
     */
    @Transactional
    public Long completeConnection(OAuthState state, String authCode) {
        Provider provider = Provider.valueOf(state.provider());
        OAuthProviderClient client = clientFor(provider);
        AuthResult result = client.exchangeCode(provider, authCode, redirectUri(client));
        OAuthToken token = result.token();
        if (token.accessToken() == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
        String encrypted = tokenCipher.encrypt(token.accessToken(), token.refreshToken());
        LocalDateTime expiresAt = token.expiresAt(LocalDateTime.now());

        ServiceConnection connection;
        if (state.purpose() == Purpose.REAUTH) {
            connection = findConnection(state.workspaceId(), state.connectionId());
            if (!isSameAccount(connection, result.externalAccountId())) {
                throw new BusinessException(ErrorCode.REAUTH_ACCOUNT_MISMATCH);
            }
            connection.reconnect(result.externalAccountId(), encrypted, token.scope(), expiresAt);
        } else {
            connection = connectionRepository.findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
                            state.workspaceId(), state.userId(), provider, ConnectionStatus.DISCONNECTED)
                    .orElse(null);
            if (connection != null) {
                if (!isSameAccount(connection, result.externalAccountId())) {
                    // 다른 계정으로 다시 연결하면 이전 계정 기준의 연동 대상은 의미가 없다.
                    targetRepository.deleteAllByConnectionId(connection.getConnectionId());
                }
                connection.reconnect(result.externalAccountId(), encrypted, token.scope(), expiresAt);
            } else {
                connection = connectionRepository.save(new ServiceConnection(state.workspaceId(), state.userId(),
                        provider, result.externalAccountId(), encrypted, token.scope(), expiresAt));
            }
        }
        connection.changeAccountLabel(result.accountLabel());
        refreshTargetStatus(connection, client);
        return connection.getConnectionId();
    }

    // ===================== 상태 조회 =====================

    /** 서비스별 연결 상태 (UC-03-02). 팀 서비스는 워크스페이스 전체, 개인 서비스는 내 연결만 */
    public List<IntegrationStatusResponse> getConnectionStatuses(Long workspaceId, Long userId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        List<ServiceConnection> connections = connectionRepository
                .findAllByWorkspaceIdAndStatusNot(workspaceId, ConnectionStatus.DISCONNECTED);
        Map<Long, List<IntegrationTarget>> targets = targetRepository
                .findAllByConnectionIdIn(connections.stream().map(ServiceConnection::getConnectionId).toList())
                .stream().collect(Collectors.groupingBy(IntegrationTarget::getConnectionId));
        Map<Long, UserSummary> users = userService
                .getUsers(connections.stream().map(ServiceConnection::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserSummary::userId, Function.identity()));

        List<IntegrationStatusResponse> result = new ArrayList<>();
        for (Provider provider : Provider.values()) {
            boolean personal = PERSONAL_PROVIDERS.contains(provider);
            List<ConnectionStatusDTO> list = connections.stream()
                    .filter(c -> c.getProvider() == provider)
                    .filter(c -> !personal || c.getUserId().equals(userId))
                    .map(c -> toDto(c, userId, users.get(c.getUserId()),
                            targets.getOrDefault(c.getConnectionId(), List.of())))
                    .toList();
            boolean configured = clients.stream().anyMatch(c -> c.supports(provider) && c.isConfigured());
            result.add(new IntegrationStatusResponse(provider, personal, configured, list));
        }
        return result;
    }

    // ===================== 연동 대상 =====================

    /** 연동 대상 후보를 외부 서비스에서 조회한다. */
    @Transactional(noRollbackFor = BusinessException.class)
    public List<TargetOption> getAvailableTargets(Long workspaceId, Long userId, Long connectionId) {
        ServiceConnection connection = getEditableConnection(workspaceId, userId, connectionId);
        return clientFor(connection.getProvider())
                .getAvailableTargets(connection.getProvider(), validAccessToken(connection));
    }

    /** 워크스페이스에서 사용할 연동 대상을 고른다. 기존 선택은 바뀐다. */
    @Transactional
    public ConnectionStatusDTO selectTargets(Long workspaceId, Long userId, Long connectionId,
                                             TargetSelectRequest request) {
        ServiceConnection connection = getEditableConnection(workspaceId, userId, connectionId);
        targetRepository.deleteAllByConnectionId(connectionId);
        targetRepository.flush();
        List<IntegrationTarget> saved = targetRepository.saveAll(request.targets().stream()
                .map(t -> new IntegrationTarget(connectionId, t.id(), t.name()))
                .toList());
        if (connection.getStatus() == ConnectionStatus.TARGET_NOT_SELECTED) {
            connection.changeStatus(ConnectionStatus.CONNECTED);
        }
        return toDto(connection, userId, userService.getUser(connection.getUserId()), saved);
    }

    /** 대상 선택 없이 연결을 완료한다. (Calendar 등 대상이 선택 사항인 서비스) */
    @Transactional
    public ConnectionStatusDTO skipTargetSelection(Long workspaceId, Long userId, Long connectionId) {
        ServiceConnection connection = getEditableConnection(workspaceId, userId, connectionId);
        if (clientFor(connection.getProvider()).requiresTarget(connection.getProvider())) {
            throw new BusinessException(ErrorCode.INTEGRATION_TARGET_NOT_SELECTED);
        }
        if (connection.getStatus() == ConnectionStatus.TARGET_NOT_SELECTED) {
            connection.changeStatus(ConnectionStatus.CONNECTED);
        }
        return toDto(connection, userId, userService.getUser(connection.getUserId()),
                targetRepository.findAllByConnectionId(connectionId));
    }

    // ===================== 해제 =====================

    /** 연결 해제 (UC-03-04). 연결한 본인 또는 팀장만 가능하다. */
    @Transactional
    public void disconnectService(Long workspaceId, Long userId, Long connectionId) {
        ServiceConnection connection = getEditableConnection(workspaceId, userId, connectionId);
        try {
            String accessToken = tokenCipher.decrypt(connection.getEncryptedTokens()).accessToken();
            if (accessToken != null) {
                clientFor(connection.getProvider()).revoke(accessToken);
            }
        } catch (BusinessException e) {
            log.info("연결 해제 중 토큰 revoke 생략: connectionId={}", connectionId);
        }
        targetRepository.deleteAllByConnectionId(connectionId);
        connection.disconnect();
    }

    // ===================== 다른 파트 제공 (docs/shared-interfaces.md) =====================

    /**
     * 유효한 Access Token을 반환한다. 만료됐으면 Refresh Token으로 갱신한다.
     * 본인 연결을 먼저 쓰고, 팀 서비스(Slack·Notion·Drive)는 본인 연결이 없으면 워크스페이스의 연결을 쓴다.
     *
     * @throws BusinessException CONNECTION_NOT_FOUND(연결 없음), REAUTH_REQUIRED(갱신 실패)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public String getAccessToken(Long workspaceId, Long userId, Provider provider) {
        ServiceConnection connection = findUsableConnection(workspaceId, userId, provider)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
        return validAccessToken(connection);
    }

    /** 연결이 없으면 빈 값. 연결은 있는데 재인증이 필요하면 REAUTH_REQUIRED */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public Optional<String> findAccessToken(Long workspaceId, Long userId, Provider provider) {
        return findUsableConnection(workspaceId, userId, provider).map(this::validAccessToken);
    }

    /** 본인이 이 서비스를 연결했는지 (재인증 필요 상태 포함) */
    public boolean hasConnection(Long workspaceId, Long userId, Provider provider) {
        return connectionRepository.findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
                workspaceId, userId, provider, ConnectionStatus.DISCONNECTED).isPresent();
    }

    /** 워크스페이스에서 선택한 연동 대상 ID 목록 (Slack 채널, Notion DB, Drive 폴더, 팀 캘린더) */
    public List<String> getConnectionTargets(Long workspaceId, Provider provider) {
        List<Long> connectionIds = connectionRepository
                .findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(workspaceId, provider, ConnectionStatus.DISCONNECTED)
                .stream().map(ServiceConnection::getConnectionId).toList();
        return targetRepository.findAllByConnectionIdIn(connectionIds).stream()
                .map(IntegrationTarget::getExternalTargetId)
                .distinct()
                .toList();
    }

    /** 외부 대상(예: Slack 채널 ID)을 연동 대상으로 고른 워크스페이스들. 외부 서비스의 실시간 이벤트를 나눠 줄 때 쓴다. */
    @Transactional(readOnly = true)
    public List<Long> findWorkspacesByTarget(Provider provider, String externalTargetId) {
        return targetRepository.findWorkspaceIdsByTarget(provider, externalTargetId, ConnectionStatus.DISCONNECTED);
    }

    /**
     * 연동 대상을 선택한 연결의 토큰과 대상 ID. 팀 캘린더처럼 "대상을 고른 사람의 토큰"으로 접근해야 할 때 쓴다.
     * 대상을 선택한 연결이 없으면 빈 값.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public Optional<TargetAccess> findTargetAccess(Long workspaceId, Provider provider) {
        List<ServiceConnection> connections = connectionRepository
                .findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(workspaceId, provider, ConnectionStatus.DISCONNECTED);
        Map<Long, List<String>> targets = targetRepository
                .findAllByConnectionIdIn(connections.stream().map(ServiceConnection::getConnectionId).toList())
                .stream().collect(Collectors.groupingBy(IntegrationTarget::getConnectionId,
                        Collectors.mapping(IntegrationTarget::getExternalTargetId, Collectors.toList())));
        return connections.stream()
                .filter(c -> targets.containsKey(c.getConnectionId()))
                .findFirst()
                .map(c -> new TargetAccess(c.getConnectionId(), c.getUserId(), validAccessToken(c),
                        targets.get(c.getConnectionId())));
    }

    public record TargetAccess(Long connectionId, Long ownerUserId, String accessToken, List<String> targetIds) {

        @Override
        public String toString() {
            return "TargetAccess[connectionId=" + connectionId + "]";
        }
    }

    public boolean isSameAccount(ServiceConnection connection, String externalAccountId) {
        return Objects.equals(connection.getExternalAccountId(), externalAccountId);
    }

    public boolean isTokenExpired(LocalDateTime expiresAt) {
        return expiresAt != null && !LocalDateTime.now().plusSeconds(EXPIRY_MARGIN_SECONDS).isBefore(expiresAt);
    }

    // ===================== 내부 =====================

    /** 토큰을 꺼내고 만료됐으면 갱신한다. 갱신할 수 없으면 REAUTH_REQUIRED로 표시하고 예외를 던진다. */
    String validAccessToken(ServiceConnection connection) {
        if (connection.getStatus() == ConnectionStatus.REAUTH_REQUIRED) {
            throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
        }
        TokenCipher.Tokens tokens = tokenCipher.decrypt(connection.getEncryptedTokens());
        if (!isTokenExpired(connection.getExpiresAt())) {
            return tokens.accessToken();
        }
        OAuthToken refreshed = null;
        if (tokens.refreshToken() != null) {
            try {
                refreshed = clientFor(connection.getProvider()).refresh(tokens.refreshToken());
            } catch (BusinessException e) {
                log.warn("토큰 갱신 실패: connectionId={}, provider={}", connection.getConnectionId(), connection.getProvider());
            }
        }
        if (refreshed == null || refreshed.accessToken() == null) {
            connection.changeStatus(ConnectionStatus.REAUTH_REQUIRED);
            throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
        }
        connection.refreshTokens(tokenCipher.encrypt(refreshed.accessToken(), refreshed.refreshToken()),
                refreshed.scope() != null ? refreshed.scope() : connection.getScope(),
                refreshed.expiresAt(LocalDateTime.now()));
        return refreshed.accessToken();
    }

    private Optional<ServiceConnection> findUsableConnection(Long workspaceId, Long userId, Provider provider) {
        Optional<ServiceConnection> own = connectionRepository.findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
                workspaceId, userId, provider, ConnectionStatus.DISCONNECTED);
        if (own.isPresent() || PERSONAL_PROVIDERS.contains(provider)) {
            return own;
        }
        List<ServiceConnection> team = connectionRepository.findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(
                workspaceId, provider, ConnectionStatus.DISCONNECTED);
        return team.stream().filter(c -> c.getStatus() != ConnectionStatus.REAUTH_REQUIRED).findFirst()
                .or(() -> team.stream().findFirst());
    }

    /** 연결한 본인 또는 팀장만 대상 선택·해제를 할 수 있다. */
    private ServiceConnection getEditableConnection(Long workspaceId, Long userId, Long connectionId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        ServiceConnection connection = findConnection(workspaceId, connectionId);
        if (!connection.getUserId().equals(userId)) {
            workspaceAccessChecker.checkOwner(workspaceId, userId);
        }
        return connection;
    }

    private ServiceConnection findConnection(Long workspaceId, Long connectionId) {
        return connectionRepository.findById(connectionId)
                .filter(c -> c.getWorkspaceId().equals(workspaceId) && c.isActive())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
    }

    /** 대상이 필요한 서비스인데 아직 선택하지 않았으면 TARGET_NOT_SELECTED, 아니면 CONNECTED */
    private void refreshTargetStatus(ServiceConnection connection, OAuthProviderClient client) {
        boolean hasTargets = connection.getConnectionId() != null
                && !targetRepository.findAllByConnectionId(connection.getConnectionId()).isEmpty();
        boolean needsTarget = client.requiresTarget(connection.getProvider()) && !hasTargets;
        connection.changeStatus(needsTarget ? ConnectionStatus.TARGET_NOT_SELECTED : ConnectionStatus.CONNECTED);
    }

    private ConnectionStatusDTO toDto(ServiceConnection c, Long currentUserId, UserSummary connectedBy,
                                      List<IntegrationTarget> targets) {
        ConnectionStatus status = c.getStatus();
        if (status != ConnectionStatus.REAUTH_REQUIRED && isTokenExpired(c.getExpiresAt()) && !hasRefreshToken(c)) {
            status = ConnectionStatus.REAUTH_REQUIRED;
        }
        return new ConnectionStatusDTO(c.getConnectionId(), c.getProvider(), status, c.getExternalAccountId(),
                c.getAccountLabel() != null ? c.getAccountLabel() : c.getExternalAccountId(),
                c.getUserId(), connectedBy == null ? null : connectedBy.name(), c.getUserId().equals(currentUserId),
                targets.stream().map(t -> new TargetResponse(t.getExternalTargetId(), t.getName())).toList());
    }

    private boolean hasRefreshToken(ServiceConnection connection) {
        try {
            return tokenCipher.decrypt(connection.getEncryptedTokens()).refreshToken() != null;
        } catch (BusinessException e) {
            return false;
        }
    }

    public OAuthProviderClient clientFor(Provider provider) {
        OAuthProviderClient client = clients.stream().filter(c -> c.supports(provider)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
        if (!client.isConfigured()) {
            throw new BusinessException(ErrorCode.OAUTH_NOT_CONFIGURED);
        }
        return client;
    }

    private String redirectUri(OAuthProviderClient client) {
        return properties.callbackUrl("/api/integrations/" + client.appKey() + "/callback");
    }
}
