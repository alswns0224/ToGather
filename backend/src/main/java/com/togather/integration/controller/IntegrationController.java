package com.togather.integration.controller;

import com.togather.auth.service.OAuthStateStore;
import com.togather.auth.service.OAuthStateStore.OAuthState;
import com.togather.auth.service.OAuthStateStore.Purpose;
import com.togather.common.auth.LoginUser;
import com.togather.common.config.TogatherProperties;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.response.ApiResponse;
import com.togather.integration.client.OAuthProviderClient.TargetOption;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.dto.ConnectionStatusDTO;
import com.togather.integration.dto.IntegrationStatusResponse;
import com.togather.integration.dto.TargetSelectRequest;
import com.togather.integration.service.IntegrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-02-01. 연결·재인증은 브라우저 이동(redirect)으로 진행한다.
 * 1) GET .../integrations/{provider}/connect → 외부 서비스 인증 화면
 * 2) 외부 서비스 → GET /api/integrations/{google|slack|notion}/callback → 연동 관리 화면으로 이동
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class IntegrationController {

    private static final String BASE = "/api/workspaces/{workspaceId}/integrations";

    private final IntegrationService integrationService;
    private final OAuthStateStore oAuthStateStore;
    private final TogatherProperties properties;

    @GetMapping(BASE)
    public ApiResponse<List<IntegrationStatusResponse>> getConnectionStatuses(@LoginUser Long userId,
                                                                             @PathVariable Long workspaceId) {
        return ApiResponse.ok(integrationService.getConnectionStatuses(workspaceId, userId));
    }

    @GetMapping(BASE + "/{provider}/connect")
    public void startConnection(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Provider provider,
                                HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String state = oAuthStateStore.issue(request.getSession(true), new OAuthState(Purpose.CONNECT,
                    workspaceId, userId, null, provider.name(), null, Instant.now()));
            response.sendRedirect(integrationService.buildConnectUrl(workspaceId, userId, provider, state));
        } catch (BusinessException e) {
            response.sendRedirect(resultUrl(workspaceId, "error=" + e.getErrorCode().name()));
        }
    }

    @GetMapping(BASE + "/connections/{connectionId}/reauth")
    public void startReauthentication(@LoginUser Long userId, @PathVariable Long workspaceId,
                                      @PathVariable Long connectionId,
                                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            ServiceConnection connection = integrationService.getConnectionForReauth(workspaceId, userId, connectionId);
            String state = oAuthStateStore.issue(request.getSession(true), new OAuthState(Purpose.REAUTH,
                    workspaceId, userId, connectionId, connection.getProvider().name(), null, Instant.now()));
            response.sendRedirect(integrationService.buildReauthUrl(connection, state));
        } catch (BusinessException e) {
            response.sendRedirect(resultUrl(workspaceId, "error=" + e.getErrorCode().name()));
        }
    }

    /** google / slack / notion 공통 콜백. 어떤 서비스·워크스페이스인지는 state로 구분한다. */
    @GetMapping("/api/integrations/{appKey}/callback")
    public void oauthCallback(@PathVariable String appKey,
                              @RequestParam(required = false) String code,
                              @RequestParam(required = false) String state,
                              @RequestParam(required = false) String error,
                              HttpServletRequest request, HttpServletResponse response) throws IOException {
        OAuthState saved;
        try {
            saved = oAuthStateStore.consume(request.getSession(false), state, Purpose.CONNECT, Purpose.REAUTH);
        } catch (BusinessException e) {
            response.sendRedirect(properties.appUrl() + "/workspaces?error=" + e.getErrorCode().name());
            return;
        }
        if (error != null || code == null) {
            // 사용자가 인증 화면에서 취소한 경우
            response.sendRedirect(resultUrl(saved.workspaceId(), "error=CANCELED&provider=" + saved.provider()));
            return;
        }
        try {
            Long connectionId = integrationService.completeConnection(saved, code);
            response.sendRedirect(resultUrl(saved.workspaceId(),
                    "connected=" + saved.provider() + "&connectionId=" + connectionId));
        } catch (BusinessException e) {
            log.warn("외부 서비스 연동 실패: provider={}, code={}", saved.provider(), e.getErrorCode());
            response.sendRedirect(resultUrl(saved.workspaceId(),
                    "error=" + e.getErrorCode().name() + "&provider=" + saved.provider()));
        }
    }

    @GetMapping(BASE + "/connections/{connectionId}/available-targets")
    public ApiResponse<List<TargetOption>> getAvailableTargets(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                               @PathVariable Long connectionId) {
        return ApiResponse.ok(integrationService.getAvailableTargets(workspaceId, userId, connectionId));
    }

    @PostMapping(BASE + "/connections/{connectionId}/targets")
    public ApiResponse<ConnectionStatusDTO> selectTargets(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                          @PathVariable Long connectionId,
                                                          @Valid @RequestBody TargetSelectRequest request) {
        return ApiResponse.ok(integrationService.selectTargets(workspaceId, userId, connectionId, request));
    }

    @PostMapping(BASE + "/connections/{connectionId}/skip-targets")
    public ApiResponse<ConnectionStatusDTO> skipTargetSelection(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                                @PathVariable Long connectionId) {
        return ApiResponse.ok(integrationService.skipTargetSelection(workspaceId, userId, connectionId));
    }

    @DeleteMapping(BASE + "/connections/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnectService(@LoginUser Long userId, @PathVariable Long workspaceId,
                                  @PathVariable Long connectionId) {
        integrationService.disconnectService(workspaceId, userId, connectionId);
    }

    /** query 값은 Enum 이름·숫자만 들어가므로 인코딩하지 않는다. */
    private String resultUrl(Long workspaceId, String query) {
        return properties.appUrl() + "/workspaces/" + workspaceId + "/integrations?" + query;
    }
}
