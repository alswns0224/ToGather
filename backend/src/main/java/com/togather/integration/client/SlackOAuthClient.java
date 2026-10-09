package com.togather.integration.client;

import static com.togather.common.util.JsonValues.bool;
import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.longValue;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.auth.dto.OAuthToken;
import com.togather.common.config.TogatherProperties;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.util.List;
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
 * Slack OAuth v2. 사용자 토큰(user_scope)으로 연동해 본인 이름으로 메시지를 작성할 수 있게 한다.
 * 주의: Slack은 Redirect URL에 https만 허용한다. 로컬 개발 시 ngrok 등으로 https 주소를 APP_URL에 지정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackOAuthClient implements OAuthProviderClient {

    /** B 파트가 필요한 권한이 늘면 여기에 추가한다. */
    static final String USER_SCOPE = String.join(",",
            "channels:read", "channels:history", "groups:read", "groups:history",
            "chat:write", "users:read", "users:read.email", "files:read");
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient externalRestClient;
    private final TogatherProperties properties;

    @Override
    public String appKey() {
        return "slack";
    }

    @Override
    public boolean supports(Provider provider) {
        return provider == Provider.SLACK;
    }

    @Override
    public boolean isConfigured() {
        return properties.slack() != null && properties.slack().isConfigured();
    }

    @Override
    public String buildAuthorizeUrl(Provider provider, String state, String redirectUri) {
        return UriComponentsBuilder.fromUriString("https://slack.com/oauth/v2/authorize")
                .queryParam("client_id", client().clientId())
                .queryParam("user_scope", USER_SCOPE)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    @Override
    public AuthResult exchangeCode(Provider provider, String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", client().clientId());
        form.add("client_secret", client().clientSecret());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        Map<String, Object> json = postForm(form);
        Map<String, Object> user = obj(json, "authed_user");
        OAuthToken token = new OAuthToken(str(user, "access_token"), str(user, "refresh_token"),
                longValue(user, "expires_in"), str(user, "scope"));
        String accountId = str(obj(json, "team"), "id") + ":" + str(user, "id");
        return new AuthResult(token, accountId);
    }

    /** 토큰 교체(rotation)를 켠 앱에서만 쓰인다. */
    @Override
    public OAuthToken refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", client().clientId());
        form.add("client_secret", client().clientSecret());
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        Map<String, Object> json = postForm(form);
        Map<String, Object> source = json.containsKey("authed_user") ? obj(json, "authed_user") : json;
        String newRefresh = str(source, "refresh_token");
        return new OAuthToken(str(source, "access_token"), newRefresh != null ? newRefresh : refreshToken,
                longValue(source, "expires_in"), str(source, "scope"));
    }

    @Override
    public boolean requiresTarget(Provider provider) {
        return true;
    }

    @Override
    public List<TargetOption> getAvailableTargets(Provider provider, String accessToken) {
        String uri = "https://slack.com/api/conversations.list?types=public_channel,private_channel&exclude_archived=true&limit=200";
        Map<String, Object> json = call(() -> externalRestClient.get()
                .uri(uri)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(JSON));
        return list(json, "channels").stream()
                .map(c -> new TargetOption(str(c, "id"), "#" + str(c, "name")))
                .toList();
    }

    @Override
    public void revoke(String accessToken) {
        try {
            externalRestClient.get()
                    .uri("https://slack.com/api/auth.revoke")
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Slack 토큰 revoke 실패: {}", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> postForm(MultiValueMap<String, String> form) {
        return call(() -> externalRestClient.post()
                .uri("https://slack.com/api/oauth.v2.access")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JSON));
    }

    /** Slack은 실패해도 HTTP 200에 {"ok": false, "error": "..."}를 보낸다. */
    private Map<String, Object> call(java.util.function.Supplier<Map<String, Object>> request) {
        try {
            Map<String, Object> json = request.get();
            if (!bool(json, "ok")) {
                log.warn("Slack API 오류: {}", str(json, "error"));
                throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
            }
            return json;
        } catch (RestClientException e) {
            log.warn("Slack API 호출 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private TogatherProperties.OAuthClient client() {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.OAUTH_NOT_CONFIGURED);
        }
        return properties.slack();
    }
}
