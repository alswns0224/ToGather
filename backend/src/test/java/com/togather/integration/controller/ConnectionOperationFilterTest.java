package com.togather.integration.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.service.ConnectionOperationRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConnectionOperationFilterTest {
    private final ConnectionOperationRegistry registry = new ConnectionOperationRegistry();
    private final ConnectionOperationFilter filter = new ConnectionOperationFilter(registry);
    private final ServiceConnection connection = new ServiceConnection(
        1L, 1L, Provider.GOOGLE_DRIVE, "account", "unused", "", null);

    @Test
    void 스트리밍_응답은_요청_스레드가_돌아가도_응답_완료까지_보호한다() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/workspaces/1/drive/files/1/download");
        request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            registry.use(connection);
            req.startAsync(req, res);
        });
        assertThatThrownBy(() -> registry.beginDisconnect(connection)).isInstanceOf(BusinessException.class);
        // 같은 서버 스레드의 다음 요청은 이전 비동기 요청의 scope를 물려받지 않는다.
        try (var nextRequest = registry.openScope()) {
            registry.use(new ServiceConnection(2L, 2L, Provider.GOOGLE_DRIVE, "other", "unused", "", null));
        }
        request.getAsyncContext().complete();
        try (var ignored = registry.beginDisconnect(connection)) {
        }
    }

    @Test
    void 요청이_실패해도_작업_예약을_반납한다() {
        var request = new MockHttpServletRequest("POST", "/api/workspaces/1/drive/files");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            registry.use(connection);
            throw new ServletException("test failure");
        })).isInstanceOf(ServletException.class);
        try (var ignored = registry.beginDisconnect(connection)) {
        }
    }
}
