package com.togather.integration.dto;

/** 로컬 해제와 외부 권한 폐기 확인 여부는 구분해서 안내한다. 토큰은 포함하지 않는다. */
public record DisconnectResult(boolean manualRevocationRequired, boolean relatedConnectionsRequireReauth) {
}
