package com.togather.auth.dto;

import com.togather.auth.domain.User;

/** 다른 파트에 제공하는 사용자 정보 (docs/shared-interfaces.md) */
public record UserSummary(Long userId, String name, String email, String profileImage) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getUserId(), user.getName(), user.getEmail(), user.getProfileImage());
    }
}
