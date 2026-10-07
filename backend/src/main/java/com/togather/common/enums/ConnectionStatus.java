package com.togather.common.enums;

/** C-00-23 연결 상태 */
public enum ConnectionStatus {
    TARGET_NOT_SELECTED, // 인증 완료, 연동 대상 미선택
    CONNECTED, // 연동 대상까지 선택해 연결 완료
    REAUTH_REQUIRED, // 토큰 만료, 재인증 필요
    DISCONNECTED; // 연결 해제됨
}
