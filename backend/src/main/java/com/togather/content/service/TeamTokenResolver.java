package com.togather.content.service;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.TargetAccess;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 팀 서비스(Slack·Notion) 조회·동기화에 쓸 토큰을 고른다.
 *
 * <p>연동 대상(채널·데이터베이스)은 그것을 고른 사람의 연결로만 접근이 보장된다. 요청자 본인의 연결은
 * 다른 Slack 워크스페이스이거나, 비공개 채널에 없거나, 그 Notion DB를 공유받지 않았을 수 있다.
 * 그래서 대상을 고른 연결의 토큰을 먼저 쓰고, 없을 때만 요청자(또는 팀) 토큰을 쓴다.
 * 메시지 작성처럼 "누가 했는지"가 중요한 작업은 이 클래스를 쓰지 않고 본인 토큰을 쓴다.
 *
 * <p>한계: A의 findTargetAccess는 대상을 고른 연결 중 첫 번째만 돌려준다. 여러 팀원이 각자 대상을 고르면
 * 두 번째 연결의 대상은 요청자 토큰으로 접근한다. (A에 대상별 연결 조회 메서드 요청 예정)
 */
@Component
@RequiredArgsConstructor
public class TeamTokenResolver {

    private final IntegrationService integrationService;

    /**
     * @param targetId    접근할 채널·데이터베이스. null이면 아무 대상이나 (사용자·채널 이름 조회 등)
     * @param requestedBy 요청한 사용자. 스케줄러면 null
     * @throws BusinessException CONNECTION_NOT_FOUND, REAUTH_REQUIRED
     */
    public String tokenFor(Long workspaceId, Provider provider, String targetId, Long requestedBy) {
        BusinessException targetOwnerError = null;
        try {
            Optional<TargetAccess> access = integrationService.findTargetAccess(workspaceId, provider);
            if (access.isPresent() && (targetId == null || access.get().targetIds().contains(targetId))) {
                return access.get().accessToken();
            }
        } catch (BusinessException e) {
            targetOwnerError = e; // 대상을 고른 사람의 재인증이 필요하면 요청자 토큰으로 시도한다.
        }
        if (requestedBy != null) {
            Optional<String> own = integrationService.findAccessToken(workspaceId, requestedBy, provider);
            if (own.isPresent()) {
                return own.get();
            }
        }
        if (targetOwnerError != null) {
            throw targetOwnerError;
        }
        throw new BusinessException(ErrorCode.CONNECTION_NOT_FOUND);
    }

    /** 토큰이 없으면 빈 값 (있으면 좋은 정보: 이름 표시, 담당자 매핑 등) */
    public Optional<String> findTokenFor(Long workspaceId, Provider provider, String targetId, Long requestedBy) {
        try {
            return Optional.of(tokenFor(workspaceId, provider, targetId, requestedBy));
        } catch (BusinessException e) {
            return Optional.empty();
        }
    }
}
