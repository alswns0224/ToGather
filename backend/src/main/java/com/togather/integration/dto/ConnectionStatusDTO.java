package com.togather.integration.dto;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import java.util.List;

/**
 * CD-DTO ConnectionStatusDTO. 토큰은 절대 넣지 않는다.
 *
 * @param accountLabel 화면에 보여줄 계정 이름 (없으면 externalAccountId)
 * @param mine         현재 사용자가 연결한 것인지 (재인증은 연결한 사람만 가능)
 */
public record ConnectionStatusDTO(
        Long connectionId,
        Provider provider,
        ConnectionStatus status,
        String externalAccountId,
        String accountLabel,
        Long connectedByUserId,
        String connectedByName,
        boolean mine,
        List<TargetResponse> targets
) {

    public record TargetResponse(String id, String name) {
    }
}
