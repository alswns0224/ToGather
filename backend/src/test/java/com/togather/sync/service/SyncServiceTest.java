package com.togather.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.sync.domain.SyncState;
import com.togather.sync.service.SyncAdapter.SyncContext;
import com.togather.sync.service.SyncAdapter.SyncResult;
import com.togather.sync.service.SyncAdapter.SyncTarget;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class SyncServiceTest {

    private static final LocalDateTime SYNCED = LocalDateTime.of(2026, 10, 7, 9, 0);

    private final SyncStateService stateService = mock(SyncStateService.class);
    private final FakeAdapter adapter = new FakeAdapter();
    private SyncService syncService;
    private SyncState state;

    @BeforeEach
    void setUp() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("fakeAdapter", adapter);
        syncService = new SyncService(factory.getBeanProvider(SyncAdapter.class), stateService);
        state = new SyncState(1L, Provider.SLACK, "C1", null);
        when(stateService.getOrCreate(eq(1L), eq(Provider.SLACK), eq("C1"), any())).thenReturn(state);
    }

    @Test
    void 처음_동기화는_since_없이_하고_성공_시각을_기록한다() {
        adapter.result = new SyncResult(3, SYNCED);

        syncService.syncNow(1L, Provider.SLACK, 7L);

        assertThat(adapter.contexts).hasSize(1);
        assertThat(adapter.contexts.getFirst().since()).isNull();
        assertThat(adapter.contexts.getFirst().requestedBy()).isEqualTo(7L);
        verify(stateService).recordSuccess(any(), eq(SYNCED), any(), any());
    }

    @Test
    void 다음_동기화는_마지막_성공_시각_이후만_가져온다() {
        state.recordSuccess(SYNCED, null, SYNCED);
        adapter.result = new SyncResult(0, SYNCED.plusMinutes(5));

        syncService.syncNow(1L, Provider.SLACK, 7L);

        assertThat(adapter.contexts.getFirst().since()).isEqualTo(SYNCED);
    }

    @Test
    void 마지막에_돌려준_cursor를_다음_동기화에_넘기고_새_cursor를_저장한다() {
        state.recordSuccess(SYNCED, "history-100", SYNCED);
        adapter.result = new SyncResult(2, SYNCED.plusMinutes(5), "history-120");

        syncService.syncNow(1L, Provider.SLACK, 7L);

        assertThat(adapter.contexts.getFirst().syncCursor()).isEqualTo("history-100");
        verify(stateService).recordSuccess(any(), eq(SYNCED.plusMinutes(5)), eq("history-120"), any());
    }

    @Test
    void cursor를_쓰지_않는_어댑터는_null로_받는다() {
        adapter.result = new SyncResult(1, SYNCED);

        syncService.syncNow(1L, Provider.SLACK, 7L);

        assertThat(adapter.contexts.getFirst().syncCursor()).isNull();
        verify(stateService).recordSuccess(any(), eq(SYNCED), eq(null), any());
    }

    @Test
    void 외부_API가_실패해도_예외를_던지지_않고_실패를_기록한다() {
        adapter.failure = new BusinessException(ErrorCode.EXTERNAL_API_ERROR);

        syncService.syncNow(1L, Provider.SLACK, 7L);

        verify(stateService).recordFailure(any(), eq("EXTERNAL_API_ERROR"), any());
        verify(stateService, never()).recordSuccess(any(), any(), any(), any());
    }

    @Test
    void 연속으로_실패하면_자동_동기화를_점점_늦춘다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 12, 0);
        SyncState failing = new SyncState(1L, Provider.SLACK, "C1", null);
        failing.recordFailure("EXTERNAL_API_ERROR", now.minusMinutes(4));
        assertThat(SyncService.isBackingOff(failing, now)).isTrue(); // 1번 실패: 5분 대기

        failing.recordFailure("EXTERNAL_API_ERROR", now.minusMinutes(9));
        assertThat(SyncService.isBackingOff(failing, now)).isTrue(); // 2번 실패: 10분 대기

        failing.recordFailure("EXTERNAL_API_ERROR", now.minusMinutes(21));
        assertThat(SyncService.isBackingOff(failing, now)).isFalse(); // 3번 실패: 20분 대기

        failing.recordSuccess(now, null, now);
        assertThat(SyncService.isBackingOff(failing, now)).isFalse();
    }

    @Test
    void 연동이_없으면_대상이_없어서_아무것도_하지_않는다() {
        adapter.targetsFailure = new BusinessException(ErrorCode.CONNECTION_NOT_FOUND);

        syncService.syncNow(1L, Provider.SLACK, 7L);

        assertThat(adapter.contexts).isEmpty();
        verify(stateService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    void 직접_요청해도_1분_안에_다시_동기화하지_않는다() {
        state.recordSuccess(SYNCED, null, java.time.LocalDateTime.now().minusSeconds(20));

        syncService.syncNow(1L, Provider.SLACK, 7L);

        assertThat(adapter.contexts).isEmpty();
    }

    static class FakeAdapter implements SyncAdapter {

        final List<SyncContext> contexts = new ArrayList<>();
        SyncResult result = new SyncResult(0, null);
        RuntimeException failure;
        RuntimeException targetsFailure;

        @Override
        public Provider provider() {
            return Provider.SLACK;
        }

        @Override
        public List<SyncTarget> findTargets(Long workspaceId, Long requestedBy) {
            if (targetsFailure != null) {
                throw targetsFailure;
            }
            return List.of(SyncTarget.team("C1"));
        }

        @Override
        public SyncResult sync(SyncContext context) {
            contexts.add(context);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
