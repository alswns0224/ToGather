package com.togather.integration.controller;

import com.togather.integration.service.ConnectionOperationRegistry;
import com.togather.integration.service.ConnectionOperationRegistry.Scope;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 업로드 등 HTTP 작업이 선택한 연결을 요청 종료까지 보호한다. Drive 스트리밍도 완료까지 유지한다. */
@Component
@RequiredArgsConstructor
public class ConnectionOperationFilter extends OncePerRequestFilter {

    private final ConnectionOperationRegistry operations;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Scope scope = operations.openScope();
        try {
            chain.doFilter(request, response);
        } finally {
            scope.detach();
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().addListener(new CompletionListener(scope));
                } catch (IllegalStateException alreadyCompleted) {
                    scope.close();
                }
            } else {
                scope.close();
            }
        }
    }

    private record CompletionListener(Scope scope) implements AsyncListener {
        @Override
        public void onComplete(AsyncEvent event) {
            scope.close();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            // 비동기 작업 취소 후 컨테이너가 완료를 알릴 때 해제한다.
        }

        @Override
        public void onError(AsyncEvent event) {
            // 오류 처리/응답 완료 전에는 연결을 해제하지 않는다.
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
