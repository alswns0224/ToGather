package com.togather.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 togather.* 설정.
 */
@ConfigurationProperties(prefix = "togather")
public record TogatherProperties(
        String appUrl,
        String tokenKey,
        Auth auth,
        OAuthClient google,
        OAuthClient slack,
        OAuthClient notion
) {

    public record Auth(boolean devLogin) {
    }

    public record OAuthClient(String clientId, String clientSecret) {

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    /** 브라우저가 돌아올 주소. 개발 중에는 Vite(5173)가 /api를 백엔드로 프록시한다. */
    public String callbackUrl(String path) {
        return appUrl + path;
    }
}
