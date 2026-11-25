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
    WORKSPACE_OWNER_REQUIRED(HttpStatus.FORBIDDEN, "팀장 또는 관리자만 수행할 수 있습니다."),
    MAIN_OWNER_REQUIRED(HttpStatus.FORBIDDEN, "팀장만 수행할 수 있습니다."),
    INVITE_LINK_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 초대 링크입니다."),
    INVITE_LINK_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 초대 링크입니다."),
    ALREADY_WORKSPACE_MEMBER(HttpStatus.CONFLICT, "이미 참여 중인 워크스페이스입니다."),
    CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "연동된 서비스가 없습니다."),
    CONNECTION_IN_USE(HttpStatus.CONFLICT,
            "이 계정으로 업로드·동기화 등 작업이 진행 중이거나 연결을 해제하고 있습니다. 작업이 끝난 뒤 다시 시도해 주세요."),
    REAUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "외부 서비스 재인증이 필요합니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "Google 로그인에 실패했습니다."),
    CANNOT_REMOVE_OWNER(HttpStatus.BAD_REQUEST, "팀장·관리자는 내보낼 수 없습니다. 관리자는 먼저 관리자에서 해제해 주세요."),
    CANNOT_CHANGE_OWNER_ROLE(HttpStatus.BAD_REQUEST, "팀장의 역할은 바꿀 수 없습니다. 팀장을 바꾸려면 팀장 넘기기를 사용해 주세요."),
    CANNOT_TRANSFER_TO_SELF(HttpStatus.BAD_REQUEST, "자기 자신에게는 팀장을 넘길 수 없습니다."),
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
    MEETING_TIME_CONFLICT(HttpStatus.CONFLICT, "참여자 중 이 시간에 다른 일정이 있는 사람이 있습니다."),
    MEETING_MANAGE_REQUIRED(HttpStatus.FORBIDDEN, "회의를 만든 사람이나 팀장·관리자만 할 수 있습니다."),
    AGENDA_NOT_FOUND(HttpStatus.NOT_FOUND, "안건을 찾을 수 없습니다."),
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "시작 시각은 종료 시각보다 이전이어야 합니다."),

    // ===== B. 수집·Slack·Notion·AI =====
    ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."),
    AI_CALL_FAILED(HttpStatus.BAD_GATEWAY, "AI 분석 호출에 실패했습니다."),
    SLACK_ACTION_FORBIDDEN(HttpStatus.FORBIDDEN, "Slack에서 허용되지 않은 작업입니다. 본인 메시지인지, 채널에 참여 중인지 확인해 주세요."),
    OWN_CONNECTION_REQUIRED(HttpStatus.FORBIDDEN, "이 서비스를 내 계정으로 연동해야 볼 수 있습니다. 연동 관리에서 연결해 주세요."),
    CONTENT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "원본 서비스에서 이 자료를 볼 권한이 없습니다."),
    SLACK_OWN_CONNECTION_REQUIRED(HttpStatus.FORBIDDEN, "메시지를 작성하려면 본인 Slack 계정을 먼저 연동해 주세요."),
    SLACK_CHANNEL_NOT_LINKED(HttpStatus.BAD_REQUEST, "연동 대상으로 선택한 채널이 아닙니다."),
    SLACK_SCOPE_REQUIRED(HttpStatus.FORBIDDEN, "Slack 읽기 권한이 부족합니다. 본인 Slack 계정을 다시 연결해 주세요."),
    SLACK_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Slack 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."),
    NOTION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Notion에서 이 문서에 접근할 수 없습니다. 연동 시 공유한 데이터베이스인지 확인해 주세요."),
    ANALYSIS_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 항목을 찾을 수 없습니다."),
    ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "이 스레드는 지금 분석 중입니다. 잠시 후 다시 확인해 주세요."),
    ANALYSIS_ALREADY_CONFIRMED(HttpStatus.BAD_REQUEST, "이미 확정된 분석은 수정할 수 없습니다."),
    ANALYSIS_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "분석을 먼저 확정해 주세요."),
    ANALYSIS_TARGET_INVALID(HttpStatus.BAD_REQUEST, "Slack 스레드의 첫 메시지만 분석할 수 있습니다."),
    ANALYSIS_OUTPUT_ALREADY_DONE(HttpStatus.CONFLICT, "이미 생성했습니다. 중복으로 만들지 않습니다."),
    ANALYSIS_ITEM_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "이 항목 유형으로는 할 수 없는 작업입니다."),
    SUGGESTION_TIME_REQUIRED(HttpStatus.BAD_REQUEST, "일시가 정해지지 않은 일정입니다. 일시를 입력한 뒤 승인해 주세요."),
    SUGGESTION_ALREADY_HANDLED(HttpStatus.CONFLICT, "이미 승인하거나 무시한 후보입니다."),
    PERSONAL_TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "할 일을 찾을 수 없습니다."),
    EVIDENCE_NOT_IN_THREAD(HttpStatus.BAD_REQUEST, "같은 스레드의 메시지만 근거로 연결할 수 있습니다."),
    NOTION_BODY_NOT_EDITABLE(HttpStatus.CONFLICT, "이미지·표·하위 블록이 있는 문서는 본문이 지워질 수 있어 Notion에서 직접 수정해 주세요."),
    NOTION_DATABASE_NOT_LINKED(HttpStatus.BAD_REQUEST, "연동된 Notion 페이지·데이터베이스가 없습니다. 연동 관리에서 문서를 만들 페이지나 데이터베이스를 선택해 주세요."),
    SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "검색 서버(BGE-M3 임베딩·Chroma)에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요."),

    // ===== C. Drive·Gmail·Task·검색 =====
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "Task를 찾을 수 없습니다."),
    TASK_STATUS_MANAGED_BY_DELIVERABLES(HttpStatus.CONFLICT,
            "산출물이 있는 Task의 상태는 제출 여부에 따라 자동으로 변경됩니다. 산출물을 제출하거나 제출을 해제해 주세요."),
    TASK_IN_ACTIVE_MEETING(HttpStatus.CONFLICT,
            "조율 중이거나 확정된 회의의 안건으로 사용 중입니다. 회의에서 안건을 해제한 뒤 삭제해 주세요."),
    DELIVERABLE_EXTENSION_MISMATCH(HttpStatus.BAD_REQUEST, "허용되지 않은 산출물 확장자입니다."),
    DRIVE_FILE_NAME_CONFLICT(HttpStatus.CONFLICT, "같은 이름의 파일이 있습니다. 새 파일 저장 또는 덮어쓸 대상을 선택해 주세요."),
    DRIVE_FILE_CHANGED(HttpStatus.CONFLICT, "선택한 파일이 변경됐습니다. 최신 파일 정보를 확인한 뒤 다시 선택해 주세요."),
    DRIVE_UPLOAD_IN_PROGRESS(HttpStatus.CONFLICT, "같은 폴더와 파일 이름으로 업로드가 진행 중입니다. 완료 후 다시 시도해 주세요."),
    DRIVE_TRASH_FORBIDDEN(HttpStatus.FORBIDDEN, "이 항목은 아직 팀장 소유로 옮겨지지 않아 휴지통으로 옮기거나 복원할 수 없습니다. 소유권 정리가 끝난 뒤 다시 시도해 주세요."),
    DRIVE_OWNER_NOT_CONNECTED(HttpStatus.CONFLICT, "팀장이 Google Drive를 연동해야 사용할 수 있습니다. 팀장에게 연동 관리에서 Drive 연결을 요청해 주세요."),
    DRIVE_OWNER_REAUTH_REQUIRED(HttpStatus.CONFLICT, "팀장의 Google Drive 연결을 다시 인증해야 합니다. 팀장에게 연동 관리에서 재인증을 요청해 주세요."),
    DRIVE_TARGET_OWNER_ONLY(HttpStatus.FORBIDDEN, "Drive 폴더는 팀장이 자기 Drive 연결로만 고를 수 있습니다."),
    DRIVE_NEW_OWNER_NOT_CONNECTED(HttpStatus.CONFLICT, "팀장을 넘겨받을 팀원이 먼저 Google Drive를 연동해야 Drive 파일 소유권도 함께 넘길 수 있습니다."),
    DRIVE_OWNERSHIP_TRANSFER_IN_PROGRESS(HttpStatus.CONFLICT, "Drive 파일 소유권을 넘기는 중입니다. 끝난 뒤 다시 시도해 주세요."),    SUGGESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "후보를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
