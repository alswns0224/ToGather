package com.togather.auth.dto;

import java.time.LocalDateTime;

/**
 * 외부 OAuth 토큰 응답. 로그·응답에 넣지 않는다.
 *
 * @param expiresInSeconds 만료까지 남은 초. 만료가 없는 토큰(Slack, Notion)은 null
 */
public record OAuthToken(String accessToken, String refreshToken, Long expiresInSeconds, String scope) {

    public LocalDateTime expiresAt(LocalDateTime now) {
        return expiresInSeconds == null ? null : now.plusSeconds(expiresInSeconds);
    }

    @Override
    public String toString() {
        return "OAuthToken[hidden]";
    }
}
