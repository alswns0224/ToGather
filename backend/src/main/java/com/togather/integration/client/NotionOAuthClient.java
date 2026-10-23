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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
    /** 페이지를 연동 대상으로 고른 경우 대상 ID 앞에 붙는다. (데이터베이스는 ID 그대로) */
    public static final String PAGE_TARGET_PREFIX = "page:";
    /** 공유 목록 검색 최대 요청 수 (100개씩) */
    private static final int MAX_SEARCH_PAGES = 5;
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
        return new AuthResult(toToken(json, null), str(json, "workspace_id"), str(json, "workspace_name"));
    }

    @Override
    public OAuthToken refresh(String refreshToken) {
        return toToken(postToken(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken)), refreshToken);
    }

    @Override
    public boolean requiresTarget(Provider provider) {
        return true;
    }

    /**
     * 통합에 공유된 데이터베이스와 페이지 목록.
     * 페이지는 대상 ID 앞에 "page:"를 붙여 구분한다. 페이지를 고르면 그 아래 하위 페이지·데이터베이스까지 가져온다.
     * 데이터베이스의 행(페이지)은 데이터베이스를 고르면 함께 오므로 목록에서 뺀다.
     */
    @Override
    public List<TargetOption> getAvailableTargets(Provider provider, String accessToken) {
        List<TargetOption> databases = new ArrayList<>();
        List<TargetOption> pages = new ArrayList<>();
        String cursor = null;
        int count = 0;
        try {
            do {
                Map<String, Object> body = new HashMap<>();
                body.put("page_size", 100);
                if (cursor != null) {
                    body.put("start_cursor", cursor);
                }
                Map<String, Object> json = externalRestClient.post()
                        .uri("https://api.notion.com/v1/search")
                        .headers(h -> {
                            h.setBearerAuth(accessToken);
                            h.set("Notion-Version", NOTION_VERSION);
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JSON);
                for (Map<String, Object> result : list(json, "results")) {
                    if (bool(result, "archived") || bool(result, "in_trash")) {
                        continue;
                    }
                    if ("database".equals(str(result, "object"))) {
                        databases.add(new TargetOption(str(result, "id"), "🗂 " + title(list(result, "title"))));
                    } else if ("page".equals(str(result, "object"))
                            && !"database_id".equals(str(obj(result, "parent"), "type"))) {
                        pages.add(new TargetOption(PAGE_TARGET_PREFIX + str(result, "id"), "📄 " + pageTitle(result)));
                    }
                }
                cursor = bool(json, "has_more") ? str(json, "next_cursor") : null;
                count++;
            } while (cursor != null && count < MAX_SEARCH_PAGES);
        } catch (RestClientException e) {
            log.warn("Notion 공유 목록 조회 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
        List<TargetOption> targets = new ArrayList<>(pages);
        targets.addAll(databases);
        return targets;
    }

    /** 연동 대상이 페이지인지 (페이지면 "page:" + 페이지 ID) */
    public static boolean isPageTarget(String targetId) {
        return targetId != null && targetId.startsWith(PAGE_TARGET_PREFIX);
    }

    /** "page:" 대상에서 페이지 ID */
    public static String pageId(String targetId) {
        return targetId.substring(PAGE_TARGET_PREFIX.length());
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

    /** 페이지 속성 중 제목(title) 속성의 글자 */
    private static String pageTitle(Map<String, Object> page) {
        return obj(page, "properties").values().stream()
                .filter(v -> v instanceof Map<?, ?> p && "title".equals(p.get("type")))
                .map(v -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> property = (Map<String, Object>) v;
                    return title(list(property, "title"));
                })
                .findFirst().orElse("(제목 없음)");
    }

    private static String title(List<Map<String, Object>> richText) {
        String title = richText.stream()
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
