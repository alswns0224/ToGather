package com.togather.integration.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.integration.domain.ServiceConnection;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ConnectionOperationRegistryTest {
    private final ConnectionOperationRegistry registry = new ConnectionOperationRegistry();

    @Test
    void 중첩_작업과_중복_토큰_조회는_바깥_작업이_끝날_때까지_보호한다() {
        var connection = connection(Provider.GOOGLE_DRIVE, "same-account");
        try (var outer = registry.openScope()) {
            registry.use(connection);
            registry.use(connection);
            try (var inner = registry.openScope()) {
                registry.use(connection);
            }
            assertThatThrownBy(() -> registry.beginDisconnect(connection)).isInstanceOf(BusinessException.class);
        }
        try (var ignored = registry.beginDisconnect(connection)) {
            // 중복 조회 때문에 사용 수가 남지 않는다.
        }
    }

    @Test
    void 같은_Google_계정의_다른_서비스_해제도_막고_다른_계정은_허용한다() {
        try (var scope = registry.openScope()) {
            registry.use(connection(Provider.GOOGLE_DRIVE, "account-a"));
            assertThatThrownBy(() -> registry.beginDisconnect(connection(Provider.GMAIL, "account-a")))
                .isInstanceOf(BusinessException.class);
            try (var ignored = registry.beginDisconnect(connection(Provider.GOOGLE_DRIVE, "account-b"))) {
                // 서로 다른 계정은 영향을 받지 않는다.
            }
        }
    }

    @Test
    void 해제가_먼저_시작되면_새_사용과_중복_해제를_거절한다() {
        var connection = connection(Provider.SLACK, "team:user");
        try (var disconnect = registry.beginDisconnect(connection); var scope = registry.openScope()) {
            assertThatThrownBy(() -> registry.use(connection)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> registry.beginDisconnect(connection)).isInstanceOf(BusinessException.class);
        }
        try (var scope = registry.openScope()) {
            registry.use(connection);
        }
    }

    @Test
    void 예외로_끝난_작업도_예약을_반납한다() {
        var connection = connection(Provider.NOTION, "notion-workspace");
        assertThatThrownBy(() -> {
            try (var scope = registry.openScope()) {
                registry.use(connection);
                throw new IllegalStateException("test failure");
            }
        }).isInstanceOf(IllegalStateException.class);
        try (var ignored = registry.beginDisconnect(connection)) {
        }
    }

    @Test
    void 해제_예약은_DB_트랜잭션_종료까지_유지한다() {
        var connection = connection(Provider.GOOGLE_DRIVE, "account");
        TransactionSynchronizationManager.initSynchronization();
        try {
            registry.beginDisconnect(connection).close();
            try (var scope = registry.openScope()) {
                assertThatThrownBy(() -> registry.use(connection)).isInstanceOf(BusinessException.class);
            }
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            try (var scope = registry.openScope()) {
                registry.use(connection);
            }
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    private static ServiceConnection connection(Provider provider, String account) {
        return new ServiceConnection(1L, 1L, provider, account, "unused", "", null);
    }
}
