package com.togather.integration.service;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.integration.domain.ServiceConnection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 한 서버에서 연결 사용과 해제를 상호 배제한다. 토큰 값은 저장하지 않는다. */
@Component
public class ConnectionOperationRegistry {

    private final Map<Key, State> states = new HashMap<>();
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    public Scope openScope() {
        Scope scope = new Scope(current.get());
        current.set(scope);
        return scope;
    }

    /** 실제 토큰이 선택됐을 때 그 연결을 현재 요청/백그라운드 작업 종료까지 사용 중으로 둔다. */
    public void use(ServiceConnection connection) {
        Scope scope = current.get();
        if (scope != null) {
            scope.root().reserve(key(connection));
            if (connection.getConnectionId() != null) {
                scope.lastUsed.put(connection.getProvider(), connection.getConnectionId());
            }
        }
    }

    /**
     * 지금 작업(가장 안쪽 범위부터 바깥으로)에서 이 서비스로 마지막에 쓴 연결.
     * 외부 서비스가 "권한 없음(401)"을 돌려줬을 때 어느 연결의 권한이 끊겼는지 알아내는 데 쓴다.
     */
    public java.util.Optional<Long> lastUsedConnection(Provider provider) {
        for (Scope scope = current.get(); scope != null; scope = scope.parent) {
            Long id = scope.lastUsed.get(provider);
            if (id != null) {
                return java.util.Optional.of(id);
            }
        }
        return java.util.Optional.empty();
    }

    public AutoCloseableLease beginDisconnect(ServiceConnection connection) {
        Key key = key(connection);
        synchronized (states) {
            State state = states.computeIfAbsent(key, ignored -> new State());
            if (state.users > 0 || state.disconnecting) {
                throw new BusinessException(ErrorCode.CONNECTION_IN_USE);
            }
            state.disconnecting = true;
        }
        return new AutoCloseableLease(() -> {
            synchronized (states) {
                states.remove(key);
            }
        });
    }

    private void acquire(Key key) {
        synchronized (states) {
            State state = states.computeIfAbsent(key, ignored -> new State());
            if (state.disconnecting) {
                throw new BusinessException(ErrorCode.CONNECTION_IN_USE);
            }
            state.users++;
        }
    }

    private void release(Key key) {
        synchronized (states) {
            State state = states.get(key);
            if (state != null && --state.users == 0) {
                states.remove(key);
            }
        }
    }

    private static Key key(ServiceConnection connection) {
        Provider provider = connection.getProvider();
        // Google 세 서비스는 같은 OAuth 앱·계정의 권한을 공유하므로 함께 보호한다.
        String app = switch (provider) {
            case GOOGLE_DRIVE, GOOGLE_CALENDAR, GMAIL -> "GOOGLE";
            default -> provider.name();
        };
        String account = connection.getExternalAccountId();
        return new Key(app, account == null ? "connection:" + connection.getConnectionId() : account);
    }

    private static void afterTransaction(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private record Key(String app, String account) {
    }

    private static class State {
        int users;
        boolean disconnecting;
    }

    public static final class AutoCloseableLease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable release;

        private AutoCloseableLease(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                afterTransaction(release);
            }
        }
    }

    public final class Scope implements AutoCloseable {
        private final Scope parent;
        private final Set<Key> used = new HashSet<>();
        private final Map<Provider, Long> lastUsed = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Scope(Scope parent) {
            this.parent = parent;
        }

        private Scope root() {
            return parent == null ? this : parent.root();
        }

        private synchronized void reserve(Key key) {
            if (!used.contains(key)) {
                acquire(key);
                used.add(key);
            }
        }

        /** 비동기 응답은 요청 스레드의 연결만 끊고, 실제 응답 종료까지 사용 예약을 유지한다. */
        public void detach() {
            if (current.get() == this) {
                if (parent == null) current.remove();
                else current.set(parent);
            }
        }

        @Override
        public void close() {
            detach();
            if (parent == null && closed.compareAndSet(false, true)) {
                afterTransaction(() -> used.forEach(ConnectionOperationRegistry.this::release));
            }
        }
    }
}
