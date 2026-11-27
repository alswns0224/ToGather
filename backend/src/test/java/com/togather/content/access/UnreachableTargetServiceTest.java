package com.togather.content.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.integration.dto.ConnectionStatusDTO;
import com.togather.integration.dto.ConnectionStatusDTO.TargetResponse;
import com.togather.integration.dto.IntegrationStatusResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 연동 관리 카드: ToGather에는 연결돼 있는데 Slack에서 채널을 나가 아무도 못 보는 채널을 경고에 띄운다. */
class UnreachableTargetServiceTest {

    private static final Long WS = 1L;

    private final ContentAccessService access = mock(ContentAccessService.class);
    private final UnreachableTargetService service = new UnreachableTargetService(access);

    @Test
    void 연결한_팀원_누구도_볼_수_없는_채널은_경고에_넣는다() {
        when(access.visibleTargets(WS, 10L, Provider.SLACK)).thenReturn(Set.of("C-OK"));
        when(access.visibleTargets(WS, 20L, Provider.SLACK)).thenReturn(Set.of());

        IntegrationStatusResponse slack = service.addUnreachable(WS, List.of(slack(
                connection(10L, ConnectionStatus.CONNECTED, "C-OK", "C-LEFT"),
                connection(20L, ConnectionStatus.CONNECTED)))).getFirst();

        assertThat(slack.parkedTargets()).containsExactly("#C-LEFT");
    }

    @Test
    void 한_명이라도_볼_수_있으면_경고하지_않는다() {
        when(access.visibleTargets(WS, 10L, Provider.SLACK)).thenReturn(Set.of());
        when(access.visibleTargets(WS, 20L, Provider.SLACK)).thenReturn(Set.of("C-TEAM"));

        IntegrationStatusResponse slack = service.addUnreachable(WS, List.of(slack(
                connection(10L, ConnectionStatus.CONNECTED, "C-TEAM"),
                connection(20L, ConnectionStatus.CONNECTED)))).getFirst();

        assertThat(slack.parkedTargets()).isEmpty();
    }

    @Test
    void 고른_채널이_없거나_Slack이_아니면_확인하지_않는다() {
        service.addUnreachable(WS, List.of(slack(connection(10L, ConnectionStatus.CONNECTED)),
                new IntegrationStatusResponse(Provider.NOTION, false, true,
                        List.of(connection(10L, ConnectionStatus.CONNECTED, "page:x")))));

        verify(access, never()).visibleTargets(any(), any(), any());
    }

    private static IntegrationStatusResponse slack(ConnectionStatusDTO... connections) {
        return new IntegrationStatusResponse(Provider.SLACK, false, true, List.of(connections));
    }

    private static ConnectionStatusDTO connection(Long userId, ConnectionStatus status, String... channels) {
        return new ConnectionStatusDTO(userId, Provider.SLACK, status, "T1:U" + userId, "팀", userId, "이름", false,
                java.util.Arrays.stream(channels).map(id -> new TargetResponse(id, "#" + id)).toList());
    }
}
