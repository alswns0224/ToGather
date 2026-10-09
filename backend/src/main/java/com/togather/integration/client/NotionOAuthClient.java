package com.togather.integration.client;

import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.longValue;
import static com.togather.common.util.JsonValues.str;

import com.togather.auth.dto.OAuthToken;
import com.togather.common.config.TogatherProperties;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Notion 공개 통합(Public integration) OAuth.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotionOAuthClient implements OAuthProviderClient {

    public static final String NOTION_VERSION = "2022-06-28";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient externalRestClient;
    private final TogatherProperties properties;

    @Override
    public String appKey() {
        return "notion";
    }

    @Override
    public boolean supports(Provider provider) {
        return provider == Provider.NOTION;
    }

    @Override
    public boolean isConfigured() {
        return properties.notion() != null && properties.notion().isConfigured();
    }

    @Override
    public String buildAuthorizeUrl(Provider provider, String state, String redirectUri) {
        return UriComponentsBuilder.fromUriString("https://api.notion.com/v1/oauth/authorize")
                .queryParam("client_id", client().clientId())
                .queryParam("response_type", "code")
                .queryParam("owner", "user")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    @Override
    public AuthResult exchangeCode(Provider provider, String code, String redirectUri) {
        Map<String, Object> json = postToken(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri));
        return new AuthResult(toToken(json, null), str(json, "workspace_id"));
    }

    @Override
    public OAuthToken refresh(String refreshToken) {
        return toToken(postToken(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken)), refreshToken);
    }

    @Override
    public boolean requiresTarget(Provider provider) {
        return true;
    }

    /** 통합에 공유된 데이터베이스 목록 */
    @Override
    public List<TargetOption> getAvailableTargets(Provider provider, String accessToken) {
        try {
            Map<String, Object> json = externalRestClient.post()
                    .uri("https://api.notion.com/v1/search")
                    .headers(h -> {
                        h.setBearerAuth(accessToken);
                        h.set("Notion-Version", NOTION_VERSION);
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filter", Map.of("property", "object", "value", "database"), "page_size", 100))
                    .retrieve()
                    .body(JSON);
            return list(json, "results").stream()
                    .map(db -> new TargetOption(str(db, "id"), title(db)))
                    .toList();
        } catch (RestClientException e) {
            log.warn("Notion 데이터베이스 조회 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /** Notion은 토큰 revoke API가 없다. 사용자가 Notion 설정에서 연결을 끊어야 완전히 해제된다. */
    @Override
    public void revoke(String accessToken) {
    }

    private Map<String, Object> postToken(Map<String, ?> body) {
        TogatherProperties.OAuthClient notion = client();
        String basic = Base64.getEncoder().encodeToString(
                (notion.clientId() + ":" + notion.clientSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            return externalRestClient.post()
                    .uri("https://api.notion.com/v1/oauth/token")
                    .headers(h -> {
                        h.setBasicAuth(basic);
                        h.set("Notion-Version", NOTION_VERSION);
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JSON);
        } catch (RestClientException e) {
            log.warn("Notion 토큰 요청 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private OAuthToken toToken(Map<String, Object> json, String fallbackRefreshToken) {
        String refresh = str(json, "refresh_token");
        return new OAuthToken(str(json, "access_token"), refresh != null ? refresh : fallbackRefreshToken,
                longValue(json, "expires_in"), null);
    }

    private static String title(Map<String, Object> database) {
        String title = list(database, "title").stream()
                .map(t -> str(t, "plain_text"))
                .filter(s -> s != null)
                .collect(Collectors.joining());
        return title.isBlank() ? "(제목 없음)" : title;
    }

    private TogatherProperties.OAuthClient client() {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.OAUTH_NOT_CONFIGURED);
        }
        return properties.notion();
    }
}
