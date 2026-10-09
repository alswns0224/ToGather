package com.togather.workspace.dto;

import com.togather.workspace.domain.InviteLink;
import java.time.LocalDateTime;

/**
 * @param url 팀원에게 보낼 초대 주소 (프론트엔드 /invite/{token})
 */
public record InviteLinkResponse(String token, String url, LocalDateTime expiresAt) {

    public static InviteLinkResponse of(InviteLink link, String appUrl) {
        return new InviteLinkResponse(link.getToken(), appUrl + "/invite/" + link.getToken(), link.getExpiresAt());
    }
}
