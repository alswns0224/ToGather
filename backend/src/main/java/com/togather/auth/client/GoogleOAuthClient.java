package com.togather.auth.client;

import static com.togather.common.util.JsonValues.longValue;
import static com.togather.common.util.JsonValues.str;

import com.togather.auth.dto.GoogleUserInfo;
import com.togather.auth.dto.OAuthToken;
import com.togather.common.config.TogatherProperties;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google OAuth 2.0. 로그인(openid)과 Drive·Calendar·Gmail 연동에 함께 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuthClient {

    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient externalRestClient;
    private final TogatherProperties properties;

    /**
     * @param offline true면 Refresh Token을 받기 위해 access_type=offline, prompt=consent를 붙인다.
     */
    public String buildAuthorizeUrl(String state, String redirectUri, String scope, boolean offline) {
        TogatherProperties.OAuthClient google = requireClient();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", google.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", scope)
                .queryParam("state", state)
                .queryParam("include_granted_scopes", "true");
        if (offline) {
            builder.queryParam("access_type", "offline").queryParam("prompt", "consent");
        } else {
            builder.queryParam("prompt", "select_account");
        }
        return builder.encode().build().toUriString();
    }

    public OAuthToken exchangeCode(String code, String redirectUri) {
        TogatherProperties.OAuthClient google = requireClient();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", google.clientId());
        form.add("client_secret", google.clientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        return toToken(postForm(form), null);
    }

    public OAuthToken refresh(String refreshToken) {
        TogatherProperties.OAuthClient google = requireClient();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", google.clientId());
        form.add("client_secret", google.clientSecret());
        form.add("grant_type", "refresh_token");
        // 갱신 응답에는 refresh_token이 없으므로 기존 값을 유지한다.
        return toToken(postForm(form), refreshToken);
    }

    public GoogleUserInfo getUserInfo(String accessToken) {
        try {
            Map<String, Object> json = externalRestClient.get()
                    .uri(USERINFO_URL)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .body(JSON);
            return new GoogleUserInfo(str(json, "sub"), str(json, "email"), str(json, "name"), str(json, "picture"));
        } catch (RestClientException e) {
            log.warn("Google userinfo 조회 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /** 연결 해제 시 토큰을 무효화한다. 실패해도 해제는 계속 진행하므로 예외를 던지지 않는다. */
    public void revoke(String token) {
        try {
            externalRestClient.post()
                    .uri(UriComponentsBuilder.fromUriString(REVOKE_URL).queryParam("token", token).build().toUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Google 토큰 revoke 실패: {}", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> postForm(MultiValueMap<String, String> form) {
        try {
            return externalRestClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JSON);
        } catch (RestClientException e) {
            log.warn("Google 토큰 요청 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private OAuthToken toToken(Map<String, Object> json, String fallbackRefreshToken) {
        String refreshToken = str(json, "refresh_token");
        return new OAuthToken(
                str(json, "access_token"),
                refreshToken != null ? refreshToken : fallbackRefreshToken,
                longValue(json, "expires_in"),
                str(json, "scope"));
    }

    private TogatherProperties.OAuthClient requireClient() {
        TogatherProperties.OAuthClient google = properties.google();
        if (google == null || !google.isConfigured()) {
            throw new BusinessException(ErrorCode.OAUTH_NOT_CONFIGURED);
        }
        return google;
    }
}
