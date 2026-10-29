package com.togather.common.event;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 워크스페이스 화면에 바뀐 내용을 바로 알려준다 (Server-Sent Events).
 * 외부 서비스에서 변경이 들어오면 publish로 "어느 서비스의 어느 대상이 바뀌었는지"만 보내고,
 * 화면이 그 대상을 다시 불러온다. 본문 같은 내용은 보내지 않는다.
 */
@Slf4j
@Service
public class WorkspaceEventService {

    /** 브라우저가 연결을 다시 맺기 전까지 유지하는 시간 */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public record ChangeEvent(String provider, String targetId) {
    }

    public SseEmitter subscribe(Long workspaceId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emitters.computeIfAbsent(workspaceId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            // 연결 직후 한 번 보내 프록시가 응답을 바로 흘려보내게 한다.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /**
     * 이 워크스페이스를 보고 있는 모든 화면에 변경을 알린다.
     * 트랜잭션 안에서 부르면 커밋된 뒤에 보낸다. (화면이 다시 불러올 때 바뀐 내용이 보이도록)
     */
    public void publish(Long workspaceId, String provider, String targetId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(workspaceId, provider, targetId);
                }
            });
            return;
        }
        send(workspaceId, provider, targetId);
    }

    private void send(Long workspaceId, String provider, String targetId) {
        List<SseEmitter> list = emitters.get(workspaceId);
        if (list == null || list.isEmpty()) {
            return;
        }
        ChangeEvent event = new ChangeEvent(provider, targetId);
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("change").data(event));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }

    /** 연결이 끊긴 화면을 정리하고, 프록시가 쉬는 연결을 끊지 않도록 주기적으로 신호를 보낸다. */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        emitters.values().forEach(list -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        });
    }
}
