package com.togather.auth.controller;

import com.togather.auth.dto.DevLoginRequest;
import com.togather.auth.dto.LoginResult;
import com.togather.auth.service.AuthService;
import com.togather.auth.service.OAuthStateStore;
import com.togather.auth.service.OAuthStateStore.OAuthState;
import com.togather.auth.service.OAuthStateStore.Purpose;
import com.togather.auth.service.SessionManager;
import com.togather.auth.service.UserService;
import com.togather.common.auth.LoginUser;
import com.togather.common.config.TogatherProperties;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-01-01. 로그인은 브라우저 이동(redirect)으로 진행한다.
 * 1) GET /api/auth/google/login → Google 인증 화면
 * 2) Google → GET /api/auth/google/callback → 세션 생성 → 앱 화면으로 이동
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private static final String CALLBACK_PATH = "/api/auth/google/callback";
    private static final String DEFAULT_REDIRECT = "/workspaces";

    private final AuthService authService;
    private final UserService userService;
    private final SessionManager sessionManager;
    private final OAuthStateStore oAuthStateStore;
    private final TogatherProperties properties;

    /** 로그인 화면에 보여줄 로그인 방식 */
    @GetMapping("/config")
    public ApiResponse<AuthConfigResponse> getConfig() {
        boolean googleEnabled = properties.google() != null && properties.google().isConfigured();
        boolean devLogin = properties.auth() != null && properties.auth().devLogin();
        return ApiResponse.ok(new AuthConfigResponse(googleEnabled, devLogin));
    }

    @GetMapping("/google/login")
    public void login(@RequestParam(required = false) String redirect,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        String state = oAuthStateStore.issue(request.getSession(true),
                new OAuthState(Purpose.LOGIN, null, null, null, null, safePath(redirect), Instant.now()));
        response.sendRedirect(authService.buildGoogleLoginUrl(state, properties.callbackUrl(CALLBACK_PATH)));
    }

    @GetMapping("/google/callback")
    public void googleCallback(@RequestParam(required = false) String code,
                               @RequestParam(required = false) String state,
                               @RequestParam(required = false) String error,
                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (error != null) {
            response.sendRedirect(appUrl("/login?error=" + encode(error)));
            return;
        }
        try {
            OAuthState saved = oAuthStateStore.consume(request.getSession(false), state, Purpose.LOGIN);
            LoginResult result = authService.loginWithGoogle(code, properties.callbackUrl(CALLBACK_PATH));
            sessionManager.createSession(request, result.userId());
            response.sendRedirect(appUrl(saved.redirectPath()));
        } catch (BusinessException e) {
            log.warn("Google 로그인 실패: {}", e.getErrorCode());
            response.sendRedirect(appUrl("/login?error=" + e.getErrorCode().name()));
        }
    }

    @GetMapping("/me")
    public ApiResponse<LoginResult> getMe(@LoginUser Long userId) {
        return ApiResponse.ok(LoginResult.from(userService.getUser(userId)));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        sessionManager.invalidate(request);
    }

    /** 개발용 로그인. togather.auth.dev-login=true일 때만 동작한다. */
    @PostMapping("/dev-login")
    public ApiResponse<LoginResult> devLogin(@Valid @RequestBody DevLoginRequest body, HttpServletRequest request) {
        if (properties.auth() == null || !properties.auth().devLogin()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        LoginResult result = authService.devLogin(body);
        sessionManager.createSession(request, result.userId());
        return ApiResponse.ok(result);
    }

    public record AuthConfigResponse(boolean googleEnabled, boolean devLoginEnabled) {
    }

    /** 외부 주소로 보내는 오픈 리다이렉트를 막기 위해 앱 내부 경로만 허용한다. */
    private static String safePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")) {
            return DEFAULT_REDIRECT;
        }
        return path;
    }

    private String appUrl(String path) {
        return properties.appUrl() + path;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
