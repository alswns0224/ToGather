package com.togather.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.integration.client.OAuthProviderClient.TargetOption;
import com.togather.integration.domain.IntegrationTarget;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.dto.IntegrationStatusResponse;
import com.togather.integration.dto.TargetSelectRequest;
import com.togather.integration.repository.IntegrationTargetRepository;
import com.togather.integration.repository.ServiceConnectionRepository;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 팀 캘린더는 고르지 않고 늘 팀장 기본 캘린더이며, Drive 하위 폴더는 연동 폴더에 더하고 뺄 수 있다. */
class TeamCalendarAndDriveTargetsTest extends IntegrationTestSupport {

    @Autowired private IntegrationService integrations;
    @Autowired private ServiceConnectionRepository connections;
    @Autowired private IntegrationTargetRepository targets;
    @Autowired private TokenCipher cipher;
    @Autowired private ConnectionOperationRegistry operations;

    private Long leader;
    private Long member;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        leader = createUser("팀장", UUID.randomUUID() + "@test.example");
        member = createUser("팀원", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(leader);
        addMember(workspaceId, member, "MEMBER");
    }

    @Test
    void 팀장도_팀원도_팀_캘린더를_고르지_않는다() {
        for (Long user : List.of(leader, member)) {
            ServiceConnection mine = connect(user, Provider.GOOGLE_CALENDAR);
            assertThatThrownBy(() -> integrations.selectTargets(workspaceId, user, mine.getConnectionId(),
                    new TargetSelectRequest(List.of(new TargetSelectRequest.Target("x@cal", "캘린더")))))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CALENDAR_TARGET_NOT_SELECTABLE));
            assertThatThrownBy(() -> integrations.getAvailableTargets(workspaceId, user, mine.getConnectionId()))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CALENDAR_TARGET_NOT_SELECTABLE));
            assertThat(targets.findAllByConnectionId(mine.getConnectionId())).isEmpty();
        }
    }

    @Test
    void 팀_일정은_팀장_기본_캘린더에_들어가고_팀원_화면에도_알려_준다() {
        ServiceConnection leaders = connect(leader, Provider.GOOGLE_CALENDAR, "leader@cal");
        connect(member, Provider.GOOGLE_CALENDAR, "member@cal");

        IntegrationStatusResponse calendar = calendarOf(integrations.getConnectionStatuses(workspaceId, member));
        assertThat(calendar.connections()).singleElement().satisfies(c -> assertThat(c.mine()).isTrue());
        assertThat(calendar.teamTarget().id()).isEqualTo("leader@cal");
        assertThat(calendar.teamTarget().chosenByUserId()).isEqualTo(leader);
        assertThat(calendar.teamTarget().chosenByName()).isEqualTo("팀장");

        try (var scope = operations.openScope()) {
            var access = integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR).orElseThrow();
            assertThat(access.connectionId()).isEqualTo(leaders.getConnectionId());
            assertThat(access.targetIds()).containsExactly("leader@cal");
            assertThat(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR, "MEMBER@cal").orElseThrow()
                .ownerUserId()).as("이미 등록한 일정은 캘린더 주인의 연결로 찾는다").isEqualTo(member);
        }
    }

    @Test
    void 팀장이_Calendar를_연결하지_않았으면_팀_캘린더가_없다() {
        connect(member, Provider.GOOGLE_CALENDAR, "member@cal");
        assertThat(calendarOf(integrations.getConnectionStatuses(workspaceId, member)).teamTarget()).isNull();
        try (var scope = operations.openScope()) {
            assertThat(integrations.findTargetAccess(workspaceId, Provider.GOOGLE_CALENDAR)).isEmpty();
        }
    }

    @Test
    void Drive_하위_폴더를_연동_폴더를_가진_연결에_더하고_뺀다() {
        ServiceConnection leaders = connect(leader, Provider.GOOGLE_DRIVE);
        targets.save(new IntegrationTarget(leaders.getConnectionId(), "root", "2"));
        connect(member, Provider.GOOGLE_DRIVE);

        integrations.addDriveTargets(workspaceId, List.of(
            new TargetOption("sub", "QA-하위", "root"),
            new TargetOption("root", "2", null)));

        assertThat(targets.findAllByConnectionId(leaders.getConnectionId()))
            .extracting(IntegrationTarget::getExternalTargetId)
            .as("이미 있는 root는 한 번만")
            .containsExactlyInAnyOrder("root", "sub");

        integrations.removeDriveTargets(workspaceId, List.of("sub"));
        assertThat(integrations.getConnectionTargets(workspaceId, Provider.GOOGLE_DRIVE)).containsExactly("root");
    }

    private ServiceConnection connect(Long owner, Provider provider) {
        return connect(owner, provider, UUID.randomUUID().toString());
    }

    private ServiceConnection connect(Long owner, Provider provider, String account) {
        var connection = new ServiceConnection(workspaceId, owner, provider, account,
            cipher.encrypt("token-" + owner, "refresh"), "scope", LocalDateTime.now().plusDays(1));
        connection.changeStatus(ConnectionStatus.CONNECTED);
        return connections.save(connection);
    }

    private static IntegrationStatusResponse calendarOf(List<IntegrationStatusResponse> all) {
        return all.stream().filter(i -> i.provider() == Provider.GOOGLE_CALENDAR).findFirst().orElseThrow();
    }
}
