package com.togather.content.access;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.integration.dto.ConnectionStatusDTO;
import com.togather.integration.dto.IntegrationStatusResponse;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 연동 관리 카드: ToGather에는 연결돼 있는데 Slack 쪽 사정(채널에서 나감·추방)으로 지금 아무 팀원도 볼 수 없는 채널을 찾는다.
 * 찾은 채널은 "연결이 끊겨 쓰지 못하는" 경고에 함께 띄운다. 누군가 그 채널에 다시 참여하면 저절로 사라진다.
 *
 * <p>팀원마다 자기 Slack 계정으로 참여한 채널을 확인한다. (ContentAccessService가 잠시 기억해 두므로 매번 Slack을 부르지는 않는다)
 */
@Service
@RequiredArgsConstructor
public class UnreachableTargetService {

    /** 팀원 계정으로 볼 수 있는지 확인하는 서비스. 지금은 Slack 채널만 (참여 채널 목록을 한 번에 받을 수 있어 가볍다) */
    static final Set<Provider> CHECKED = Set.of(Provider.SLACK);

    private final ContentAccessService contentAccess;

    public List<IntegrationStatusResponse> addUnreachable(Long workspaceId, List<IntegrationStatusResponse> items) {
        return items.stream().map(item -> CHECKED.contains(item.provider()) ? withUnreachable(workspaceId, item) : item)
                .toList();
    }

    private IntegrationStatusResponse withUnreachable(Long workspaceId, IntegrationStatusResponse item) {
        List<ConnectionStatusDTO> usable = item.connections().stream()
                .filter(c -> c.status() != ConnectionStatus.REAUTH_REQUIRED)
                .toList();
        Map<String, String> chosen = new LinkedHashMap<>();
        usable.forEach(c -> c.targets().forEach(t -> chosen.putIfAbsent(t.id(), t.name() != null ? t.name() : t.id())));
        if (chosen.isEmpty()) {
            return item;
        }
        Set<String> visible = new HashSet<>();
        for (Long member : usable.stream().map(ConnectionStatusDTO::connectedByUserId).distinct().toList()) {
            visible.addAll(contentAccess.visibleTargets(workspaceId, member, item.provider()));
            if (visible.containsAll(chosen.keySet())) {
                return item;
            }
        }
        Set<String> names = new LinkedHashSet<>(item.parkedTargets());
        chosen.forEach((id, name) -> {
            if (!visible.contains(id)) {
                names.add(name);
            }
        });
        return item.withParkedTargets(List.copyOf(names));
    }
}
