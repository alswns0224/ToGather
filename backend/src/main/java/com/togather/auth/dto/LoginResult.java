package com.togather.auth.dto;

/** CD-DTO LoginResult. 로그인 직후와 /api/auth/me 응답에 쓴다. */
public record LoginResult(Long userId, String name, String email, String profileImage) {

    public static LoginResult from(UserSummary user) {
        return new LoginResult(user.userId(), user.name(), user.email(), user.profileImage());
    }
}
