package com.togather.integration.client;

import com.togather.auth.dto.OAuthToken;
import com.togather.common.enums.Provider;
import java.util.List;

/**
 * 외부 서비스별 OAuth 연동 클라이언트. Google(Drive·Calendar·Gmail), Slack, Notion 구현이 있다.
 */
public interface OAuthProviderClient {

    /** 콜백 경로에 쓰는 이름: /api/integrations/{appKey}/callback */
    String appKey();

    boolean supports(Provider provider);

    boolean isConfigured();

    String buildAuthorizeUrl(Provider provider, String state, String redirectUri);

    AuthResult exchangeCode(Provider provider, String code, String redirectUri);

    /** Refresh Token으로 갱신한다. 지원하지 않으면 null */
    OAuthToken refresh(String refreshToken);

    /** 연동 대상 후보. 대상 선택이 필요 없는 서비스(Gmail)는 빈 목록 */
    List<TargetOption> getAvailableTargets(Provider provider, String accessToken);

    /** 대상 선택이 필요한 서비스인지 */
    boolean requiresTarget(Provider provider);

    /** 연결 해제 시 토큰 무효화. 실패해도 예외를 던지지 않는다. */
    void revoke(String accessToken);

    /** @param externalAccountId 재인증 시 같은 계정인지 비교하는 값 */
    record AuthResult(OAuthToken token, String externalAccountId) {
    }

    record TargetOption(String id, String name) {
    }
}
