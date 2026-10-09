package com.togather.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 모든 에러 코드는 여기에 모은다. 각 파트는 자기 구역에 필요한 코드를 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ===== 공통 =====
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 데이터를 찾을 수 없습니다."),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "외부 서비스 호출에 실패했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // ===== A. 기반·연동·일정 =====
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "워크스페이스를 찾을 수 없습니다."),
    WORKSPACE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "워크스페이스 멤버가 아닙니다."),
    WORKSPACE_OWNER_REQUIRED(HttpStatus.FORBIDDEN, "팀장(OWNER)만 수행할 수 있습니다."),
    INVITE_LINK_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 초대 링크입니다."),
    INVITE_LINK_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 초대 링크입니다."),
    ALREADY_WORKSPACE_MEMBER(HttpStatus.CONFLICT, "이미 참여 중인 워크스페이스입니다."),
    CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "연동된 서비스가 없습니다."),
    REAUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "외부 서비스 재인증이 필요합니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "Google 로그인에 실패했습니다."),
    CANNOT_REMOVE_OWNER(HttpStatus.BAD_REQUEST, "팀장은 내보낼 수 없습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "워크스페이스 팀원이 아닙니다."),
    OAUTH_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "외부 서비스 OAuth 설정(client id/secret)이 없습니다."),
    OAUTH_STATE_MISMATCH(HttpStatus.BAD_REQUEST, "인증 요청이 만료되었거나 올바르지 않습니다. 다시 시도해 주세요."),
    REAUTH_ACCOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "기존에 연동한 계정과 다른 계정입니다."),
    TOKEN_KEY_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "토큰 암호화 키(TOGATHER_TOKEN_KEY)가 올바르지 않습니다."),
    INTEGRATION_TARGET_NOT_SELECTED(HttpStatus.BAD_REQUEST, "연동 대상을 먼저 선택해 주세요."),
    CALENDAR_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."),
    MEETING_NOT_FOUND(HttpStatus.NOT_FOUND, "회의를 찾을 수 없습니다."),
    MEETING_NOT_SCHEDULING(HttpStatus.BAD_REQUEST, "일정 조율 중인 회의만 변경할 수 있습니다."),
    MEETING_NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "회의 참여자가 아닙니다."),
    AGENDA_NOT_FOUND(HttpStatus.NOT_FOUND, "안건을 찾을 수 없습니다."),
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "시작 시각은 종료 시각보다 이전이어야 합니다."),

    // ===== B. 수집·Slack·Notion·AI =====
    ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."),
    AI_CALL_FAILED(HttpStatus.BAD_GATEWAY, "AI 분석 호출에 실패했습니다."),

    // ===== C. Drive·Gmail·Task·검색 =====
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "Task를 찾을 수 없습니다."),
    DELIVERABLE_EXTENSION_MISMATCH(HttpStatus.BAD_REQUEST, "허용되지 않은 산출물 확장자입니다."),
    SUGGESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "후보를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
