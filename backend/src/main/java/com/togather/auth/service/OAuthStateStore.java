package com.togather.auth.service;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * OAuth 요청의 state 값을 세션에 저장한다. 콜백에서 state가 일치하는지 확인해 CSRF를 막고,
 * 인증 전에 정해 둔 정보(워크스페이스, 재인증 대상 연결, 로그인 후 이동할 경로)를 꺼낸다.
 */
@Component
public class OAuthStateStore {

    private static final String SESSION_KEY = "OAUTH_STATES";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Purpose { LOGIN, CONNECT, REAUTH }

    public record OAuthState(Purpose purpose, Long workspaceId, Long userId, Long connectionId, String provider,
                             String redirectPath, Instant issuedAt) implements Serializable {
    }

    public String issue(HttpSession session, OAuthState state) {
        String value = randomToken();
        Map<String, OAuthState> states = states(session);
        states.entrySet().removeIf(e -> isExpired(e.getValue()));
        states.put(value, state);
        session.setAttribute(SESSION_KEY, states);
        return value;
    }

    public OAuthState consume(HttpSession session, String value, Purpose... allowed) {
        if (session == null || value == null) {
            throw new BusinessException(ErrorCode.OAUTH_STATE_MISMATCH);
        }
        Map<String, OAuthState> states = states(session);
        OAuthState state = states.remove(value);
        session.setAttribute(SESSION_KEY, states);
        if (state == null || isExpired(state) || !isAllowed(state.purpose(), allowed)) {
            throw new BusinessException(ErrorCode.OAUTH_STATE_MISMATCH);
        }
        return state;
    }

    public static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, OAuthState> states(HttpSession session) {
        Object value = session.getAttribute(SESSION_KEY);
        return value instanceof Map<?, ?> map ? new HashMap<>((Map<String, OAuthState>) map) : new HashMap<>();
    }

    private boolean isExpired(OAuthState state) {
        return state.issuedAt().plus(TTL).isBefore(Instant.now());
    }

    private boolean isAllowed(Purpose purpose, Purpose[] allowed) {
        for (Purpose p : allowed) {
            if (p == purpose) {
                return true;
            }
        }
        return false;
    }
}
