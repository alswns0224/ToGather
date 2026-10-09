package com.togather.auth.service;

import com.togather.auth.client.GoogleOAuthClient;
import com.togather.auth.domain.User;
import com.togather.auth.dto.DevLoginRequest;
import com.togather.auth.dto.GoogleUserInfo;
import com.togather.auth.dto.LoginResult;
import com.togather.auth.dto.OAuthToken;
import com.togather.auth.dto.UserSummary;
import com.togather.auth.repository.UserRepository;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-01-05. Google 계정 로그인 (UC-01-01, SD-01-01)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    static final String LOGIN_SCOPE = "openid email profile";

    private final GoogleOAuthClient googleOAuthClient;
    private final UserRepository userRepository;

    public String buildGoogleLoginUrl(String state, String redirectUri) {
        return googleOAuthClient.buildAuthorizeUrl(state, redirectUri, LOGIN_SCOPE, false);
    }

    /** 인가 코드로 Google 사용자 정보를 받아 사용자를 조회하거나 처음이면 저장한다. */
    @Transactional
    public LoginResult loginWithGoogle(String authCode, String redirectUri) {
        OAuthToken token = googleOAuthClient.exchangeCode(authCode, redirectUri);
        if (token.accessToken() == null) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        GoogleUserInfo info = googleOAuthClient.getUserInfo(token.accessToken());
        if (info.sub() == null || info.email() == null) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        String name = info.name() != null ? info.name() : info.email();
        return LoginResult.from(UserSummary.from(saveOrUpdate(info.sub(), info.email(), name, info.picture())));
    }

    /** 개발용 로그인. Google 설정 없이 이메일로 사용자를 만든다. (togather.auth.dev-login=true일 때만 호출) */
    @Transactional
    public LoginResult devLogin(DevLoginRequest request) {
        String sub = "dev:" + request.email().toLowerCase();
        return LoginResult.from(UserSummary.from(saveOrUpdate(sub, request.email(), request.name(), null)));
    }

    private User saveOrUpdate(String googleSub, String email, String name, String picture) {
        return userRepository.findByGoogleSub(googleSub)
                .map(user -> {
                    user.updateProfile(name, picture);
                    return user;
                })
                .orElseGet(() -> userRepository.save(new User(googleSub, email, name, picture)));
    }
}
