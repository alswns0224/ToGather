package com.togather.integration.service;

import com.togather.auth.dto.OAuthToken;
import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.OAuthStateStore.OAuthState;
import com.togather.auth.service.OAuthStateStore.Purpose;
import com.togather.auth.service.UserService;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.auth.WorkspaceChangeGuard;
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
import com.togather.integration.dto.DisconnectResult;
import com.togather.integration.dto.TargetSelectRequest;
import com.togather.integration.event.ServiceConnectedEvent;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ConnectionOperationRegistry operations;
    private final ApplicationEventPublisher events;
    /** Drive guard가 이 서비스를 쓰므로 생성 시점이 아니라 호출할 때 꺼낸다. */
    private final ObjectProvider<WorkspaceChangeGuard> guards;

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
        if (connection.getConnectionId() == null) {
            connection = connectionRepository.save(connection);
        }
        restoreParkedTargets(connection);
        refreshTargetStatus(connection, client);
        events.publishEvent(new ServiceConnectedEvent(connection.getWorkspaceId(), connection.getUserId(), provider));
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
            IntegrationStatusResponse.TeamTarget teamTarget = provider == Provider.GOOGLE_CALENDAR
                    ? teamCalendar(workspaceId, connections, users) : null;
            result.add(new IntegrationStatusResponse(provider, personal, configured, list, teamTarget,
                    personal ? List.of() : parkedTargetNames(workspaceId, provider, connections, targets)));
        }
        return result;
    }

    /** 끊긴 연결(해제·재인증 필요)에 남아 있고, 지금 쓰는 연결에는 없는 팀 대상 이름 */
    private List<String> parkedTargetNames(Long workspaceId, Provider provider, List<ServiceConnection> activeConnections,
                                           Map<Long, List<IntegrationTarget>> activeTargets) {
        Set<String> usable = activeConnections.stream()
                .filter(c -> c.getProvider() == provider && c.getStatus() != ConnectionStatus.REAUTH_REQUIRED)
                .flatMap(c -> activeTargets.getOrDefault(c.getConnectionId(), List.of()).stream())
                .map(IntegrationTarget::getExternalTargetId).collect(Collectors.toSet());
        List<IntegrationTarget> stuck = new ArrayList<>(parkedTargets(workspaceId, provider));
        activeConnections.stream()
                .filter(c -> c.getProvider() == provider && c.getStatus() == ConnectionStatus.REAUTH_REQUIRED)
                .forEach(c -> stuck.addAll(activeTargets.getOrDefault(c.getConnectionId(), List.of())));
        return stuck.stream()
                .filter(t -> !usable.contains(t.getExternalTargetId()))
                .map(t -> t.getName() != null ? t.getName() : t.getExternalTargetId())
                .distinct().toList();
    }

    /** 팀 일정이 들어가는 캘린더: 팀장의 기본 캘린더 (팀장이 Calendar를 연결하지 않았으면 null) */
    private IntegrationStatusResponse.TeamTarget teamCalendar(Long workspaceId, List<ServiceConnection> connections,
                                                             Map<Long, UserSummary> users) {
        return workspaceAccessChecker.findMainOwner(workspaceId)
                .flatMap(owner -> connections.stream()
                        .filter(c -> c.getProvider() == Provider.GOOGLE_CALENDAR && c.getUserId().equals(owner))
                        .filter(c -> c.getExternalAccountId() != null)
                        .findFirst())
                .map(c -> {
                    UserSummary by = users.get(c.getUserId());
                    String account = c.getAccountLabel() != null ? c.getAccountLabel() : c.getExternalAccountId();
                    return new IntegrationStatusResponse.TeamTarget(c.getExternalAccountId(), account,
                            c.getUserId(), by == null ? null : by.name());
                })
                .orElse(null);
    }

    // ===================== 연동 대상 =====================

    /** 연동 대상 후보를 외부 서비스에서 조회한다. */
    @Transactional(noRollbackFor = BusinessException.class)
    public List<TargetOption> getAvailableTargets(Long workspaceId, Long userId, Long connectionId) {
        ServiceConnection connection = getEditableConnection(workspaceId, userId, connectionId);
        requireDriveOwnerSelection(connection, userId);
        return clientFor(connection.getProvider())
                .getAvailableTargets(connection.getProvider(), validAccessToken(connection));
    }

    /** 워크스페이스에서 사용할 연동 대상을 고른다. 기존 선택은 바뀐다. */
    @Transactional
    public ConnectionStatusDTO selectTargets(Long workspaceId, Long userId, Long connectionId,
                                             TargetSelectRequest request) {
        ServiceConnection connection = getEditableConnection(workspaceId, userId, connectionId);
        requireDriveOwnerSelection(connection, userId);
        if (connection.getProvider() == Provider.GOOGLE_DRIVE) {
            // 선택 창에 나오는 폴더(팀장 소유, ToGather 휴지통 제외)만 고를 수 있다.
            Set<String> allowed = visibleTargetOptions(connection);
            if (allowed != null && request.targets().stream().anyMatch(target -> !allowed.contains(target.id()))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "고를 수 없는 Drive 폴더가 있습니다. ToGather 휴지통 폴더와 팀장 소유가 아닌 폴더는 연동할 수 없습니다.");
            }
        }
        if (connection.getProvider() == Provider.SLACK
                && request.targets().stream().anyMatch(target -> target.id().startsWith("D"))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Slack DM은 개인 AI 업무 보조에서만 수집합니다.");
        }
        targetRepository.deleteAllByConnectionId(connectionId);
        targetRepository.flush();
        List<IntegrationTarget> saved = targetRepository.saveAll(request.targets().stream()
                .map(t -> new IntegrationTarget(connectionId, t.id(), t.name()))
                .toList());
        if (!PERSONAL_PROVIDERS.contains(connection.getProvider())) {
            // 팀 자료 서비스(Slack·Notion·Drive)의 연동 대상은 팀 전체에 하나다. 내 선택 창에 보였던 대상만 내 선택대로
            // 바꾸고(다른 팀원 연결에 있던 같은 대상은 지움), 내 계정으로 볼 수 없어 선택 창에 없던 다른 사람의 대상은 그대로 둔다.
            Set<String> chosen = request.targets().stream().map(TargetSelectRequest.Target::id).collect(Collectors.toSet());
            Set<String> visibleToMe = visibleTargetOptions(connection);
            for (ServiceConnection other : connectionRepository.findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(
                    workspaceId, connection.getProvider(), ConnectionStatus.DISCONNECTED)) {
                if (other.getConnectionId().equals(connectionId)) {
                    continue;
                }
                targetRepository.findAllByConnectionIdIn(List.of(other.getConnectionId())).stream()
                        .filter(t -> chosen.contains(t.getExternalTargetId())
                                || (visibleToMe != null && visibleToMe.contains(t.getExternalTargetId())))
                        .forEach(targetRepository::delete);
            }
            // 끊긴 연결에 보관해 둔 대상도, 내가 볼 수 있었던 것은 이번 선택으로 정리한다. (되살아나지 않게)
            parkedTargets(workspaceId, connection.getProvider()).stream()
                    .filter(t -> chosen.contains(t.getExternalTargetId())
                            || (visibleToMe != null && visibleToMe.contains(t.getExternalTargetId())))
                    .forEach(targetRepository::delete);
        }
        if (connection.getStatus() == ConnectionStatus.TARGET_NOT_SELECTED) {
            connection.changeStatus(ConnectionStatus.CONNECTED);
        }
        return toDto(connection, userId, userService.getUser(connection.getUserId()), saved);
    }

    /**
     * Drive 폴더는 팀장이 자기 연결로만 고른다. 팀 Drive의 모든 항목을 팀장 소유로 두기 위해서다.
     * Calendar는 고를 대상이 없다. 팀 일정은 늘 팀장의 기본 캘린더에 들어간다. (Gmail처럼 연결만 한다)
     */
    private void requireDriveOwnerSelection(ServiceConnection connection, Long userId) {
        if (connection.getProvider() == Provider.GOOGLE_CALENDAR) {
            throw new BusinessException(ErrorCode.CALENDAR_TARGET_NOT_SELECTABLE);
        }
        if (connection.getProvider() == Provider.GOOGLE_DRIVE
                && (!connection.getUserId().equals(userId) || !isMainOwner(connection))) {
            throw new BusinessException(ErrorCode.DRIVE_TARGET_OWNER_ONLY);
        }
    }

    // ===================== Drive 하위 폴더 연동 =====================

    /**
     * 연동한 Drive 폴더 아래에 새로 생긴 폴더(ToGather에서 만든 폴더, 휴지통에서 복원한 폴더)를 연동 폴더에 더한다.
     * 선택 창에서 폴더를 고르면 그 아래 폴더도 함께 고르는 것과 같은 규칙이다. 이미 있는 폴더는 건너뛴다.
     */
    @Transactional
    public void addDriveTargets(Long workspaceId, List<TargetOption> folders) {
        List<ServiceConnection> drives = connectionRepository.findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(
                workspaceId, Provider.GOOGLE_DRIVE, ConnectionStatus.DISCONNECTED);
        Map<Long, List<IntegrationTarget>> existing = targetRepository
                .findAllByConnectionIdIn(drives.stream().map(ServiceConnection::getConnectionId).toList())
                .stream().collect(Collectors.groupingBy(IntegrationTarget::getConnectionId));
        // 지금 폴더를 가진 연결(보통 팀장)에 붙인다. 아직 없으면 팀장 연결.
        Optional<ServiceConnection> holder = drives.stream().filter(c -> existing.containsKey(c.getConnectionId())).findFirst()
                .or(() -> drives.stream().filter(this::isMainOwner).findFirst());
        if (holder.isEmpty()) {
            return;
        }
        Set<String> known = existing.values().stream().flatMap(List::stream)
                .map(IntegrationTarget::getExternalTargetId).collect(Collectors.toSet());
        List<IntegrationTarget> added = folders.stream()
                .filter(f -> f.id() != null && known.add(f.id()))
                .map(f -> new IntegrationTarget(holder.get().getConnectionId(), f.id(), f.name()))
                .toList();
        targetRepository.saveAll(added);
    }

    /** 휴지통으로 옮긴 Drive 폴더(와 그 아래 연동 폴더)를 연동 폴더에서 뺀다. */
    @Transactional
    public void removeDriveTargets(Long workspaceId, java.util.Collection<String> folderIds) {
        if (folderIds.isEmpty()) {
            return;
        }
        List<Long> drives = connectionRepository.findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(
                        workspaceId, Provider.GOOGLE_DRIVE, ConnectionStatus.DISCONNECTED).stream()
                .map(ServiceConnection::getConnectionId).toList();
        targetRepository.findAllByConnectionIdIn(drives).stream()
                .filter(t -> folderIds.contains(t.getExternalTargetId()))
                .forEach(targetRepository::delete);
    }

    // ===================== 원본에서 지워진 대상 =====================

    /**
     * 원본에서 지워진 연동 대상(Notion 페이지·데이터베이스)을 연동 대상에서 뺀다. 끊긴 연결에 보관해 둔 것도 함께 지운다.
     * 보관된 대상은 동기화되지 않아 지워진 것을 따로 알 수 없으므로, 남겨 두면 "연결이 끊겨 쓰지 못하는 대상"으로 계속 보인다.
     */
    @Transactional
    public void removeDeletedTargets(Long workspaceId, Provider provider, java.util.Collection<String> targetIds) {
        removeTargets(connectionRepository.findAllByWorkspaceIdAndProvider(workspaceId, provider), targetIds);
    }

    /** 외부 계정(예: Notion 워크스페이스)으로 연결한 모든 워크스페이스에서 지워진 대상을 뺀다. (웹훅용) */
    @Transactional
    public void removeDeletedTargetsByExternalAccount(Provider provider, String externalAccountId,
                                                      java.util.Collection<String> targetIds) {
        removeTargets(connectionRepository.findAllByProviderAndExternalAccountId(provider, externalAccountId), targetIds);
    }

    private void removeTargets(List<ServiceConnection> connections, java.util.Collection<String> targetIds) {
        if (connections.isEmpty() || targetIds.isEmpty()) {
            return;
        }
        List<IntegrationTarget> removed = targetRepository
                .findAllByConnectionIdIn(connections.stream().map(ServiceConnection::getConnectionId).toList()).stream()
                .filter(t -> targetIds.contains(t.getExternalTargetId()))
                .toList();
        if (!removed.isEmpty()) {
            targetRepository.deleteAll(removed);
            log.info("원본에서 지워진 연동 대상 정리: provider={}, removed={}", connections.get(0).getProvider(), removed.size());
        }
    }

    private boolean isMainOwner(ServiceConnection connection) {
        return workspaceAccessChecker.findMainOwner(connection.getWorkspaceId())
                .map(connection.getUserId()::equals).orElse(false);
    }

    /**
     * 이 연결의 계정으로 고를 수 있는(선택 창에 나오는) 대상 ID. 조회에 실패하면 null:
     * 그때는 다른 팀원의 대상을 하나도 지우지 않는다. (볼 수 있었는지 모르는 대상을 지우지 않도록)
     */
    private Set<String> visibleTargetOptions(ServiceConnection connection) {
        try {
            return clientFor(connection.getProvider())
                    .getAvailableTargets(connection.getProvider(), validAccessToken(connection)).stream()
                    .map(TargetOption::id).collect(Collectors.toSet());
        } catch (RuntimeException e) {
            log.info("선택 가능한 대상을 확인하지 못해 다른 팀원의 대상은 유지: connectionId={}", connection.getConnectionId());
            return null;
        }
    }

    /** 이 서비스를 지금 쓸 수 있는 팀원 연결들의 토큰 (먼저 연결한 순서) */
    public List<String> usableAccessTokens(Long workspaceId, Provider provider) {
        List<String> tokens = new ArrayList<>();
        for (ServiceConnection c : connectionRepository.findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(
                workspaceId, provider, ConnectionStatus.DISCONNECTED)) {
            if (c.getStatus() == ConnectionStatus.REAUTH_REQUIRED) {
                continue;
            }
            try {
                tokens.add(validAccessToken(c));
            } catch (BusinessException e) {
                log.info("토큰을 쓸 수 없는 연결 건너뜀: connectionId={}, code={}", c.getConnectionId(), e.getErrorCode());
            }
        }
        return tokens;
    }

    // ===================== 연결이 끊길 때 팀 대상 지키기 =====================

    /** 팀이 고른 채널·페이지를 다른 팀원 연결로 넘길 수 있는 서비스. Drive는 팀장만 폴더를 가지므로 넘기지 않고 보관만 한다. */
    private static final Set<Provider> HANDOVER_PROVIDERS = Set.of(Provider.SLACK, Provider.NOTION);

    /**
     * 연결이 끊기거나(해제) 쓸 수 없게 되면(재인증 필요) 그 연결이 가진 팀 대상을, 그 대상을 볼 수 있는 다른 팀원 연결로 옮긴다.
     * 아무도 볼 수 없는 대상은 원래 연결에 그대로 남겨 둔다(보관). 해제된 연결의 대상은 목록에 쓰이지 않다가,
     * 같은 서비스를 누군가 다시 연결해 그 대상이 보이면 {@link #restoreParkedTargets}가 되살린다.
     */
    private void handOverTargets(ServiceConnection leaving) {
        if (!HANDOVER_PROVIDERS.contains(leaving.getProvider())) {
            return;
        }
        List<IntegrationTarget> owned = targetRepository.findAllByConnectionId(leaving.getConnectionId());
        if (owned.isEmpty()) {
            return;
        }
        List<ServiceConnection> others = connectionRepository.findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(
                        leaving.getWorkspaceId(), leaving.getProvider(), ConnectionStatus.DISCONNECTED).stream()
                .filter(c -> !c.getConnectionId().equals(leaving.getConnectionId()))
                .filter(c -> c.getStatus() != ConnectionStatus.REAUTH_REQUIRED)
                .toList();
        List<IntegrationTarget> remaining = new ArrayList<>(owned);
        for (ServiceConnection other : others) {
            if (remaining.isEmpty()) {
                break;
            }
            Set<String> visible = visibleTargetOptions(other);
            if (visible == null) {
                continue;
            }
            Set<String> already = targetRepository.findAllByConnectionId(other.getConnectionId()).stream()
                    .map(IntegrationTarget::getExternalTargetId).collect(Collectors.toSet());
            for (IntegrationTarget target : List.copyOf(remaining)) {
                if (visible.contains(target.getExternalTargetId())) {
                    if (already.add(target.getExternalTargetId())) {
                        targetRepository.save(new IntegrationTarget(other.getConnectionId(),
                                target.getExternalTargetId(), target.getName()));
                    }
                    targetRepository.delete(target);
                    remaining.remove(target);
                }
            }
        }
        log.info("연결이 끊겨 팀 대상 이관: connectionId={}, provider={}, moved={}, parked={}", leaving.getConnectionId(),
                leaving.getProvider(), owned.size() - remaining.size(), remaining.size());
    }

    /**
     * 새로 연결(또는 다시 연결)했을 때, 예전에 끊긴 연결에 보관해 둔 팀 대상 중 이 연결로 볼 수 있는 것을 되살린다.
     * Drive 폴더는 팀장 연결로만 되살린다.
     */
    private void restoreParkedTargets(ServiceConnection connection) {
        Provider provider = connection.getProvider();
        if (!HANDOVER_PROVIDERS.contains(provider) && provider != Provider.GOOGLE_DRIVE) {
            return;
        }
        if (provider == Provider.GOOGLE_DRIVE && !isMainOwner(connection)) {
            return;
        }
        List<IntegrationTarget> parked = parkedTargets(connection.getWorkspaceId(), provider);
        if (parked.isEmpty()) {
            return;
        }
        Set<String> visible = visibleTargetOptions(connection);
        if (visible == null) {
            return;
        }
        Set<String> active = new java.util.HashSet<>(getConnectionTargets(connection.getWorkspaceId(), provider));
        for (IntegrationTarget target : parked) {
            if (!visible.contains(target.getExternalTargetId())) {
                continue;
            }
            if (active.add(target.getExternalTargetId())) {
                targetRepository.save(new IntegrationTarget(connection.getConnectionId(),
                        target.getExternalTargetId(), target.getName()));
            }
            targetRepository.delete(target);
        }
    }

    /** 해제된 연결에 보관된 팀 대상 */
    private List<IntegrationTarget> parkedTargets(Long workspaceId, Provider provider) {
        List<Long> disconnected = connectionRepository.findAllByWorkspaceIdAndProvider(workspaceId, provider).stream()
                .filter(c -> c.getStatus() == ConnectionStatus.DISCONNECTED)
                .map(ServiceConnection::getConnectionId).toList();
        return disconnected.isEmpty() ? List.of() : targetRepository.findAllByConnectionIdIn(disconnected);
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
    public DisconnectResult disconnectService(Long workspaceId, Long userId, Long connectionId) {
        ServiceConnection connection = getEditableConnection(workspaceId, userId, connectionId, true);
        if (!connection.isActive()) {
            // 로컬 해제 기록만으로 과거 외부 폐기 성공 여부를 단정하지 않는다.
            return new DisconnectResult(true, false);
        }
        guards.orderedStream()
                .forEach(guard -> guard.beforeDisconnect(workspaceId, connection.getUserId(), connection.getProvider()));
        try (var lease = operations.beginDisconnect(connection)) {
            if (connectionRepository.findCurrentStatus(connectionId).orElse(ConnectionStatus.DISCONNECTED)
                    == ConnectionStatus.DISCONNECTED) {
                return new DisconnectResult(true, false);
            }
            boolean manualRevocationRequired = false;
            boolean shared = isTokenShared(connection);
            try {
                String accessToken = tokenCipher.decrypt(connection.getEncryptedTokens()).accessToken();
                if (shared) {
                    // 같은 계정을 다른 워크스페이스·서비스 연결이 쓰고 있다. 외부에서 폐기하면 그 연결까지 끊기므로 여기서만 지운다.
                    log.info("같은 계정의 다른 연결이 있어 외부 토큰 폐기를 건너뜀: connectionId={}", connectionId);
                } else if (accessToken == null || accessToken.isBlank()) {
                    manualRevocationRequired = true;
                } else {
                    clientFor(connection.getProvider()).revoke(accessToken);
                }
            } catch (BusinessException e) {
                manualRevocationRequired = true;
                log.info("외부 토큰 폐기를 확인하지 못함: connectionId={}, code={}", connectionId, e.getErrorCode());
            }
            // 본인이 직접 끊었다: 볼 수 있는 다른 팀원 연결이 있으면 그쪽으로 넘기고, 남은 것은 지운다.
            // (보관하지 않는다. 보관·되살리기와 "연결이 끊겨 쓰지 못하는" 안내는 권한 만료처럼 원치 않게 끊긴 경우에만 쓴다)
            handOverTargets(connection);
            targetRepository.deleteAllByConnectionId(connection.getConnectionId());
            connection.disconnect();
            boolean relatedReauth = !shared && !manualRevocationRequired && markRelatedGoogleConnections(connection);
            return new DisconnectResult(manualRevocationRequired, relatedReauth);
        }
    }

    /**
     * 내보낸 팀원의 연결을 모두 지운다. 외부 토큰은 폐기를 시도하고(실패해도 진행), 연결 기록은 남기지 않는다.
     * 그 사람이 고른 연동 대상(팀 채널·페이지·폴더)은 같은 서비스를 연결한 다른 팀원에게 넘기고,
     * 넘길 사람이 없으면 지운다. (팀이 다시 골라야 한다)
     *
     * @return 지운 연결 수
     */
    @Transactional
    public int purgeMemberConnections(Long workspaceId, Long userId) {
        List<ServiceConnection> mine = connectionRepository.findAllByWorkspaceIdAndUserId(workspaceId, userId);
        for (ServiceConnection connection : mine) {
            if (connection.isActive() && !isTokenShared(connection)) {
                try {
                    String accessToken = tokenCipher.decrypt(connection.getEncryptedTokens()).accessToken();
                    if (accessToken != null && !accessToken.isBlank()) {
                        clientFor(connection.getProvider()).revoke(accessToken);
                    }
                } catch (RuntimeException e) {
                    log.info("내보낸 팀원의 토큰 폐기를 확인하지 못함(연결은 지움): connectionId={}, error={}",
                            connection.getConnectionId(),
                            e instanceof BusinessException be ? be.getErrorCode() : e.getClass().getSimpleName());
                }
            }
            handOverTargets(workspaceId, userId, connection);
            targetRepository.deleteAllByConnectionId(connection.getConnectionId());
        }
        connectionRepository.deleteAll(mine);
        connectionRepository.flush();
        return mine.size();
    }

    private void handOverTargets(Long workspaceId, Long leavingUserId, ServiceConnection connection) {
        List<IntegrationTarget> chosen = targetRepository.findAllByConnectionIdIn(List.of(connection.getConnectionId()));
        if (chosen.isEmpty()) {
            return;
        }
        Optional<ServiceConnection> heir = connectionRepository
                .findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(workspaceId, connection.getProvider(),
                        ConnectionStatus.DISCONNECTED).stream()
                .filter(other -> !other.getUserId().equals(leavingUserId))
                .findFirst();
        if (heir.isEmpty()) {
            return;
        }
        Set<String> already = targetRepository.findAllByConnectionIdIn(List.of(heir.get().getConnectionId())).stream()
                .map(IntegrationTarget::getExternalTargetId).collect(Collectors.toSet());
        for (IntegrationTarget target : chosen) {
            if (!already.contains(target.getExternalTargetId())) {
                targetRepository.save(new IntegrationTarget(heir.get().getConnectionId(), target.getExternalTargetId(),
                        target.getName()));
            }
        }
    }

    /**
     * 같은 외부 계정을 다른 연결이 쓰고 있는지. Slack·Notion은 같은 사람이 여러 워크스페이스에서 연결해도 토큰이 하나라,
     * 한 곳에서 외부 폐기(auth.revoke 등)를 하면 다른 워크스페이스의 연결까지 끊긴다. Google은 Drive·Calendar·Gmail이 한 계정을 함께 쓴다.
     */
    private boolean isTokenShared(ServiceConnection connection) {
        if (connection.getExternalAccountId() == null) {
            return false;
        }
        Set<Provider> family = Set.of(Provider.GOOGLE_DRIVE, Provider.GOOGLE_CALENDAR, Provider.GMAIL)
                .contains(connection.getProvider())
                ? Set.of(Provider.GOOGLE_DRIVE, Provider.GOOGLE_CALENDAR, Provider.GMAIL)
                : Set.of(connection.getProvider());
        return connectionRepository.findAllByProviderInAndExternalAccountIdAndStatusNot(family,
                        connection.getExternalAccountId(), ConnectionStatus.DISCONNECTED).stream()
                .anyMatch(other -> !other.getConnectionId().equals(connection.getConnectionId()));
    }

    /** Google 폐기는 같은 계정의 프로젝트 권한 전체에 적용되므로 다른 연결을 정상 연결로 남기지 않는다. */
    private boolean markRelatedGoogleConnections(ServiceConnection disconnected) {
        Set<Provider> googleProviders = Set.of(Provider.GOOGLE_DRIVE, Provider.GOOGLE_CALENDAR, Provider.GMAIL);
        if (!googleProviders.contains(disconnected.getProvider())) {
            return false;
        }
        boolean changed = false;
        List<ServiceConnection> relatedConnections = connectionRepository
                .findAllByProviderInAndExternalAccountIdAndStatusNot(googleProviders,
                        disconnected.getExternalAccountId(), ConnectionStatus.DISCONNECTED);
        for (ServiceConnection related : relatedConnections) {
            if (!related.getConnectionId().equals(disconnected.getConnectionId())) {
                related.changeStatus(ConnectionStatus.REAUTH_REQUIRED);
                changed = true;
            }
        }
        return changed;
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

    /** 개인 입력 수집 전용. 팀원의 연결로 대체하지 않으며 채널 선택 여부와 무관하다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public Optional<OwnAccess> findOwnAccess(Long workspaceId, Long userId, Provider provider) {
        try {
            workspaceAccessChecker.checkMember(workspaceId, userId);
        } catch (BusinessException denied) {
            // 내부 권한 검사에서 rollback-only가 된 경우에도 원래 접근 거절을 유지한다.
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw denied;
        }
        return connectionRepository.findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
                workspaceId, userId, provider, ConnectionStatus.DISCONNECTED)
                .map(connection -> {
                    String token = validAccessToken(connection);
                    return new OwnAccess(connection.getConnectionId(), connection.getExternalAccountId(), connection.getScope(), token);
                });
    }

    public record OwnAccess(Long connectionId, String externalAccountId, String scope, String accessToken) {
        @Override
        public String toString() {
            return "OwnAccess[connectionId=" + connectionId + "]";
        }
    }

    /** 본인이 이 서비스를 연결했는지 (재인증 필요 상태 포함) */
    public boolean hasConnection(Long workspaceId, Long userId, Provider provider) {
        return connectionRepository.findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
                workspaceId, userId, provider, ConnectionStatus.DISCONNECTED).isPresent();
    }

    /** 본인 연결의 외부 계정 ID (Slack은 "팀ID:사용자ID"). 연결하지 않았으면 빈 값 */
    public Optional<String> findOwnExternalAccountId(Long workspaceId, Long userId, Provider provider) {
        return connectionRepository.findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
                workspaceId, userId, provider, ConnectionStatus.DISCONNECTED).map(ServiceConnection::getExternalAccountId);
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

    /**
     * 연동 대상 ID → 이름 (연동 관리에서 고를 때 저장한 이름. 예: Drive 폴더 이름).
     * 이름이 없으면 값이 null이다.
     */
    @Transactional(readOnly = true)
    public Map<String, String> getConnectionTargetNames(Long workspaceId, Provider provider) {
        List<Long> connectionIds = connectionRepository
                .findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(workspaceId, provider, ConnectionStatus.DISCONNECTED)
                .stream().map(ServiceConnection::getConnectionId).toList();
        Map<String, String> names = new java.util.LinkedHashMap<>();
        for (IntegrationTarget target : targetRepository.findAllByConnectionIdIn(connectionIds)) {
            names.putIfAbsent(target.getExternalTargetId(), target.getName());
        }
        return names;
    }

    /** 외부 대상(예: Slack 채널 ID)을 연동 대상으로 고른 워크스페이스들. 외부 서비스의 실시간 이벤트를 나눠 줄 때 쓴다. */
    @Transactional(readOnly = true)
    public List<Long> findWorkspacesByTarget(Provider provider, String externalTargetId) {
        return targetRepository.findWorkspaceIdsByTarget(provider, externalTargetId, ConnectionStatus.DISCONNECTED);
    }

    /** 이 외부 계정(예: Slack "팀ID:사용자ID")으로 연결한 ToGather 사용자. 개인 자료(DM) 웹훅을 본인에게 전할 때 쓴다. */
    @Transactional(readOnly = true)
    public List<ConnectionOwner> findOwnersByExternalAccount(Provider provider, String externalAccountId) {
        return connectionRepository
                .findAllByProviderAndExternalAccountIdAndStatusNot(provider, externalAccountId, ConnectionStatus.DISCONNECTED)
                .stream().map(c -> new ConnectionOwner(c.getWorkspaceId(), c.getUserId())).distinct().toList();
    }

    public record ConnectionOwner(Long workspaceId, Long userId) {
    }

    /** 외부 계정(예: Notion 워크스페이스 ID)을 연동한 워크스페이스들. Notion 웹훅을 나눠 줄 때 쓴다. */
    @Transactional(readOnly = true)
    public List<Long> findWorkspacesByExternalAccount(Provider provider, String externalAccountId) {
        return connectionRepository
                .findAllByProviderAndExternalAccountIdAndStatusNot(provider, externalAccountId, ConnectionStatus.DISCONNECTED)
                .stream().map(ServiceConnection::getWorkspaceId).distinct().toList();
    }

    /**
     * 연동 대상을 선택한 연결의 토큰과 대상 ID. 팀 캘린더처럼 "대상을 고른 사람의 토큰"으로 접근해야 할 때 쓴다.
     * 대상을 선택한 연결이 없으면 빈 값.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public Optional<TargetAccess> findTargetAccess(Long workspaceId, Provider provider) {
        return resolveTargetAccess(workspaceId, provider, null);
    }

    /** 특정 캘린더 등 기존 원본의 대상을 실제 선택한 연결만 반환한다. 다른 대상으로 대체하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public Optional<TargetAccess> findTargetAccess(Long workspaceId, Provider provider, String targetId) {
        return resolveTargetAccess(workspaceId, provider, targetId);
    }

    private Optional<TargetAccess> resolveTargetAccess(Long workspaceId, Provider provider, String targetId) {
        List<ServiceConnection> connections = connectionRepository
                .findAllByWorkspaceIdAndProviderAndStatusNotOrderByCreatedAtAsc(workspaceId, provider, ConnectionStatus.DISCONNECTED);
        if (provider == Provider.GOOGLE_CALENDAR) {
            return teamCalendarAccess(workspaceId, connections, targetId);
        }
        Map<Long, List<String>> targets = targetRepository
                .findAllByConnectionIdIn(connections.stream().map(ServiceConnection::getConnectionId).toList())
                .stream().collect(Collectors.groupingBy(IntegrationTarget::getConnectionId,
                        Collectors.mapping(IntegrationTarget::getExternalTargetId, Collectors.toList())));
        return connections.stream()
                .filter(c -> targets.containsKey(c.getConnectionId())
                        && (targetId == null || targets.get(c.getConnectionId()).contains(targetId)))
                .findFirst()
                .map(c -> new TargetAccess(c.getConnectionId(), c.getUserId(), validAccessToken(c),
                        targets.get(c.getConnectionId())));
    }

    /**
     * 팀 캘린더 = 팀장의 기본 캘린더. 따로 고르지 않는다. (Gmail처럼 각자 연결만 한다)
     * calendarId가 있으면(이미 등록한 팀 일정) 그 캘린더 주인의 연결을 쓴다. 팀장을 넘긴 뒤에도 예전 팀장 캘린더의 일정을 고칠 수 있게 하려는 것이다.
     * Google 기본 캘린더 ID는 계정 이메일이고, 연결의 externalAccountId도 그 이메일이다.
     */
    private Optional<TargetAccess> teamCalendarAccess(Long workspaceId, List<ServiceConnection> connections,
                                                      String calendarId) {
        Optional<ServiceConnection> chosen = calendarId != null
                ? connections.stream().filter(c -> calendarId.equalsIgnoreCase(c.getExternalAccountId())).findFirst()
                : workspaceAccessChecker.findMainOwner(workspaceId)
                        .flatMap(owner -> connections.stream().filter(c -> c.getUserId().equals(owner)).findFirst());
        return chosen.filter(c -> c.getExternalAccountId() != null)
                .map(c -> new TargetAccess(c.getConnectionId(), c.getUserId(), validAccessToken(c),
                        List.of(c.getExternalAccountId())));
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

    /**
     * 외부 서비스가 이 연결의 토큰을 "권한 없음(401)"으로 거절했다. (외부에서 권한을 끊은 경우 등)
     * 바로 재인증 필요로 바꾸지 않고 먼저 토큰 갱신을 한 번 해 본다. 갱신되면(잠깐 만료된 토큰) 그대로 두고,
     * 갱신할 수 없으면 REAUTH_REQUIRED로 바꿔 연동 관리·대시보드에 바로 재인증 안내가 나가게 한다.
     *
     * @return 재인증 필요로 바꿨으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reportUnauthorized(Long connectionId) {
        return connectionRepository.findById(connectionId).map(this::markUnauthorized).orElse(false);
    }

    /** 이 사용자의 개인 연결(Calendar·Gmail 등)이 권한 없음으로 거절됐을 때 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reportUnauthorized(Long workspaceId, Long userId, Provider provider) {
        return connectionRepository.findFirstByWorkspaceIdAndUserIdAndProviderAndStatusNot(
                        workspaceId, userId, provider, ConnectionStatus.DISCONNECTED)
                .map(this::markUnauthorized)
                .orElse(false);
    }

    private boolean markUnauthorized(ServiceConnection connection) {
        Long connectionId = connection.getConnectionId();
        if (!connection.isActive() || connection.getStatus() == ConnectionStatus.REAUTH_REQUIRED) {
            return false;
        }
        try {
            String refreshToken = tokenCipher.decrypt(connection.getEncryptedTokens()).refreshToken();
            if (refreshToken != null) {
                OAuthToken refreshed = clientFor(connection.getProvider()).refresh(refreshToken);
                if (refreshed != null && refreshed.accessToken() != null) {
                    connection.refreshTokens(tokenCipher.encrypt(refreshed.accessToken(), refreshed.refreshToken()),
                            refreshed.scope() != null ? refreshed.scope() : connection.getScope(),
                            refreshed.expiresAt(LocalDateTime.now()));
                    return false;
                }
            }
        } catch (BusinessException e) {
            log.info("권한 없음 응답 뒤 토큰 갱신 실패: connectionId={}, code={}", connectionId, e.getErrorCode());
        }
        connection.changeStatus(ConnectionStatus.REAUTH_REQUIRED);
        log.warn("외부 서비스가 권한을 거절해 재인증 필요로 표시: connectionId={}, provider={}",
                connectionId, connection.getProvider());
        handOverTargets(connection);
        return true;
    }

    /** 토큰을 꺼내고 만료됐으면 갱신한다. 갱신할 수 없으면 REAUTH_REQUIRED로 표시하고 예외를 던진다. */
    String validAccessToken(ServiceConnection connection) {
        operations.use(connection);
        ConnectionStatus currentStatus = connectionRepository.findCurrentStatus(connection.getConnectionId())
                .orElse(ConnectionStatus.DISCONNECTED);
        if (currentStatus == ConnectionStatus.DISCONNECTED) {
            throw new BusinessException(ErrorCode.CONNECTION_NOT_FOUND);
        }
        if (currentStatus == ConnectionStatus.REAUTH_REQUIRED || connection.getStatus() == ConnectionStatus.REAUTH_REQUIRED) {
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
        return getEditableConnection(workspaceId, userId, connectionId, false);
    }

    private ServiceConnection getEditableConnection(Long workspaceId, Long userId, Long connectionId,
                                                    boolean includeDisconnected) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        ServiceConnection connection = includeDisconnected
                ? connectionRepository.findById(connectionId)
                    .filter(c -> c.getWorkspaceId().equals(workspaceId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND))
                : findConnection(workspaceId, connectionId);
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
        // Drive 폴더는 팀장만 고르므로 팀원의 Drive 연결은 대상 없이 바로 연결됨이다.
        boolean memberDrive = connection.getProvider() == Provider.GOOGLE_DRIVE && !isMainOwner(connection);
        boolean needsTarget = client.requiresTarget(connection.getProvider()) && !hasTargets && !memberDrive;
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
