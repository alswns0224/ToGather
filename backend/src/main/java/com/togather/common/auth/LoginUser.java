package com.togather.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controller 파라미터에 현재 로그인 사용자 ID(Long)를 주입한다. 로그인하지 않았으면 401 UNAUTHORIZED.
 *
 * <pre>
 * public ApiResponse&lt;...&gt; getTasks(@LoginUser Long userId, ...)
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUser {
}
