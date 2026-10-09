package com.togather.auth.service;

import com.togather.common.auth.LoginUserArgumentResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * C-01-06. 로그인 세션 관리. 서버 세션(JSESSIONID 쿠키)에 사용자 ID를 저장한다.
 */
@Component
public class SessionManager {

    /** 기존 세션을 버리고 새 세션을 만들어 세션 고정 공격을 막는다. 반환값은 세션 ID. */
    public String createSession(HttpServletRequest request, Long userId) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(LoginUserArgumentResolver.SESSION_USER_ID, userId);
        return session.getId();
    }

    public void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
