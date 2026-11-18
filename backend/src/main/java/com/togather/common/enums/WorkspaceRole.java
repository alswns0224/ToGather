package com.togather.common.enums;

/** C-00-21 워크스페이스 내 역할 */
public enum WorkspaceRole {
    OWNER, // 팀장. 워크스페이스에 한 명. 만든 사람이 처음 팀장이고, 다른 팀원에게 넘길 수 있다.
    ADMIN, // 관리자. 팀장이 임명한다. 초대 링크 관리·일반 팀원 내보내기만 할 수 있다.
    MEMBER; // 초대 링크로 참여한 팀원
}
