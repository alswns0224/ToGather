package com.togather.integration.dto;

import com.togather.common.enums.Provider;
import java.util.List;

/**
 * 연동 관리 화면의 서비스 한 줄 (UI-03)
 *
 * @param personal    true면 사용자마다 각자 연결하는 서비스(Gmail, Calendar). connections에는 내 연결만 들어간다.
 * @param configured  서버에 OAuth 앱 설정(client id/secret)이 있는지. false면 연결 버튼을 막는다.
 * @param connections 비어 있으면 연결 안 됨
 * @param teamTarget  팀장이 고른 팀 대상(지금은 Calendar의 팀 캘린더). 개인 서비스라 connections에 팀장 연결이 없어도
 *                    팀원이 어떤 캘린더가 쓰이는지 알 수 있게 따로 준다. 없으면 null
 */
public record IntegrationStatusResponse(
        Provider provider,
        boolean personal,
        boolean configured,
        List<ConnectionStatusDTO> connections,
        TeamTarget teamTarget
) {

    public IntegrationStatusResponse(Provider provider, boolean personal, boolean configured,
                                     List<ConnectionStatusDTO> connections) {
        this(provider, personal, configured, connections, null);
    }

    /** 팀 대상과 그것을 고른 사람 */
    public record TeamTarget(String id, String name, Long chosenByUserId, String chosenByName) {
    }
}
