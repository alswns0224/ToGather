package com.togather.integration.client;

import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.str;

import com.togather.auth.client.GoogleOAuthClient;
import com.togather.auth.dto.GoogleUserInfo;
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google Drive · Calendar · Gmail 연동. 서비스마다 별도 연결(ServiceConnection)과 권한 범위를 가진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleIntegrationClient implements OAuthProviderClient {

    private static final String BASE_SCOPE = "openid email ";
    private static final Map<Provider, String> SCOPES = Map.of(
            Provider.GOOGLE_DRIVE, "https://www.googleapis.com/auth/drive",
            Provider.GOOGLE_CALENDAR, "https://www.googleapis.com/auth/calendar",
            Provider.GMAIL, "https://www.googleapis.com/auth/gmail.readonly");
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final GoogleOAuthClient googleOAuthClient;
    private final RestClient externalRestClient;
    private final TogatherProperties properties;

    @Override
    public String appKey() {
        return "google";
    }

    @Override
    public boolean supports(Provider provider) {
        return SCOPES.containsKey(provider);
    }

    @Override
    public boolean isConfigured() {
        return properties.google() != null && properties.google().isConfigured();
    }

    @Override
    public String buildAuthorizeUrl(Provider provider, String state, String redirectUri) {
        return googleOAuthClient.buildAuthorizeUrl(state, redirectUri, BASE_SCOPE + SCOPES.get(provider), true);
    }

    @Override
    public AuthResult exchangeCode(Provider provider, String code, String redirectUri) {
        OAuthToken token = googleOAuthClient.exchangeCode(code, redirectUri);
        GoogleUserInfo user = googleOAuthClient.getUserInfo(token.accessToken());
        return new AuthResult(token, user.email(), user.email());
    }

    @Override
    public OAuthToken refresh(String refreshToken) {
        return googleOAuthClient.refresh(refreshToken);
    }

    @Override
    public boolean requiresTarget(Provider provider) {
        // Gmail은 대상 없이 사용, Calendar는 팀 캘린더 선택이 선택 사항
        return provider == Provider.GOOGLE_DRIVE;
    }

    @Override
    public List<TargetOption> getAvailableTargets(Provider provider, String accessToken) {
        return switch (provider) {
            case GOOGLE_DRIVE -> getFolders(accessToken);
            case GOOGLE_CALENDAR -> getCalendars(accessToken);
            default -> List.of();
        };
    }

    @Override
    public void revoke(String accessToken) {
        googleOAuthClient.revoke(accessToken);
    }

    private List<TargetOption> getFolders(String accessToken) {
        String uri = UriComponentsBuilder.fromUriString("https://www.googleapis.com/drive/v3/files")
                .queryParam("q", "mimeType='application/vnd.google-apps.folder' and trashed=false")
                .queryParam("fields", "files(id,name)")
                .queryParam("orderBy", "modifiedTime desc")
                .queryParam("pageSize", 100)
                .encode().build().toUriString();
        return list(get(uri, accessToken), "files").stream()
                .map(f -> new TargetOption(str(f, "id"), str(f, "name")))
                .toList();
    }

    private List<TargetOption> getCalendars(String accessToken) {
        Map<String, Object> json = get(
                "https://www.googleapis.com/calendar/v3/users/me/calendarList?minAccessRole=writer", accessToken);
        return list(json, "items").stream()
                .map(c -> new TargetOption(str(c, "id"), str(c, "summary")))
                .toList();
    }

    private Map<String, Object> get(String uri, String accessToken) {
        try {
            return externalRestClient.get()
                    .uri(uri)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .body(JSON);
        } catch (RestClientException e) {
            log.warn("Google 연동 대상 조회 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }
}
