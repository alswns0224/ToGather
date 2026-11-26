package com.togather.content.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveItem;
import com.togather.content.service.TeamTokenResolver;
import com.togather.integration.client.OAuthProviderClient.TargetOption;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.OwnAccess;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 새 폴더 만들기와, 상위 폴더를 고를 때 함께 연동된 하위 폴더의 휴지통·복원 (Drive API는 mock) */
class DriveSubfolderTargetTest extends IntegrationTestSupport {

    private static final Provider DRIVE = Provider.GOOGLE_DRIVE;
    private static final String ROOT = "root-folder";
    private static final String SUB = "sub-folder";
    private static final String DEEP = "deep-folder";
    private static final String TRASH = "trash-folder";
    private static final String MY_DRIVE = "my-drive-root";
    private static final String LEADER_TOKEN = "leader-token";
    private static final String LEADER_EMAIL = "leader@test.example";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 26, 10, 0);

    @Autowired private DriveTrashService trash;
    @Autowired private DriveContentService drive;
    @MockitoBean private DriveApiClient api;
    @MockitoBean private IntegrationService integration;
    @MockitoBean private TeamTokenResolver teamTokenResolver;

    private Long leader;
    private Long member;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        leader = createUser("팀장", UUID.randomUUID() + "@test.example");
        member = createUser("팀원", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(leader);
        addMember(workspaceId, member, "MEMBER");

        when(integration.findOwnAccess(workspaceId, leader, DRIVE))
            .thenReturn(Optional.of(new OwnAccess(1L, LEADER_EMAIL, "drive", LEADER_TOKEN)));
        when(integration.findOwnExternalAccountId(workspaceId, leader, DRIVE)).thenReturn(Optional.of(LEADER_EMAIL));
        when(integration.hasConnection(eq(workspaceId), any(), eq(DRIVE))).thenReturn(true);

        when(api.createFolder(eq(LEADER_TOKEN), anyString(), isNull(), any())).thenReturn(folder(TRASH, MY_DRIVE));
        when(api.getItem(LEADER_TOKEN, TRASH)).thenReturn(folder(TRASH, MY_DRIVE));
        when(api.getItem(LEADER_TOKEN, MY_DRIVE)).thenReturn(new DriveItem(MY_DRIVE, "내 드라이브",
            DriveApiClient.FOLDER_MIME, List.of(), false, null, null, null, List.of(LEADER_EMAIL)));
        when(api.getItem(LEADER_TOKEN, ROOT)).thenReturn(folder(ROOT, MY_DRIVE));
        when(api.getItem(LEADER_TOKEN, SUB)).thenReturn(folder(SUB, ROOT));
        when(api.getItem(LEADER_TOKEN, DEEP)).thenReturn(folder(DEEP, SUB));
    }

    @Test
    void 연동_폴더_안에_만든_폴더는_팀장_토큰으로_만들고_연동_폴더에_더한다() {
        when(integration.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(ROOT));
        when(api.createFolder(LEADER_TOKEN, "회의록", ROOT, Map.of())).thenReturn(folder("new-folder", ROOT, "회의록"));

        var created = drive.createFolder(workspaceId, member, ROOT, "  회의록 ");

        assertThat(created.id()).isEqualTo("new-folder");
        assertThat(created.name()).isEqualTo("회의록");
        verify(integration).addDriveTargets(workspaceId, List.of(new TargetOption("new-folder", "회의록", ROOT)));
    }

    @Test
    void 연동하지_않은_하위_폴더_안에_만들면_연동_폴더에_더하지_않는다() {
        when(integration.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(ROOT));
        when(api.createFolder(LEADER_TOKEN, "메모", SUB, Map.of())).thenReturn(folder("memo", SUB, "메모"));

        drive.createFolder(workspaceId, leader, SUB, "메모");

        verify(integration, never()).addDriveTargets(any(), anyList());
    }

    @Test
    void 연동_폴더_밖이나_빈_이름에는_만들_수_없다() {
        when(integration.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(ROOT));
        when(api.getItem(LEADER_TOKEN, "outside")).thenReturn(folder("outside", MY_DRIVE));

        assertThatThrownBy(() -> drive.createFolder(workspaceId, leader, "outside", "x"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INTEGRATION_TARGET_NOT_SELECTED);
        assertThatThrownBy(() -> drive.createFolder(workspaceId, leader, ROOT, "  "))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(api, never()).createFolder(anyString(), anyString(), eq(ROOT), any());
    }

    @Test
    void 함께_연동된_하위_폴더는_휴지통으로_옮기고_그_아래_연동_폴더까지_연동에서_뺀다() {
        when(integration.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(ROOT, SUB, DEEP));

        trash.trash(workspaceId, member, SUB);

        verify(api).moveItem(LEADER_TOKEN, SUB, ROOT, TRASH);
        verify(integration).removeDriveTargets(workspaceId, List.of(SUB, DEEP));
    }

    @Test
    void 맨_위_연동_폴더는_여전히_휴지통으로_옮길_수_없다() {
        when(integration.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(ROOT, SUB));

        assertThatThrownBy(() -> trash.trash(workspaceId, leader, ROOT))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(api, never()).moveItem(anyString(), anyString(), anyString(), anyString());
        verify(integration, never()).removeDriveTargets(any(), anyCollection());
    }

    @Test
    void 연동_폴더_바로_아래로_복원한_폴더는_그_아래_폴더까지_다시_연동한다() {
        when(integration.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(ROOT, SUB, DEEP));
        trash.trash(workspaceId, leader, SUB);

        when(integration.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(ROOT));
        when(api.getItem(LEADER_TOKEN, SUB)).thenReturn(folder(SUB, TRASH));
        when(api.listChildren(LEADER_TOKEN, SUB)).thenReturn(List.of(folder(DEEP, SUB)));
        when(api.listChildren(LEADER_TOKEN, DEEP)).thenReturn(List.of());

        trash.restore(workspaceId, leader, SUB);

        verify(api).moveItem(LEADER_TOKEN, SUB, TRASH, ROOT);
        verify(integration).addDriveTargets(workspaceId, List.of(
            new TargetOption(SUB, SUB, TRASH),
            new TargetOption(DEEP, DEEP, SUB)));
    }

    private static DriveItem folder(String id, String parent) {
        return folder(id, parent, id);
    }

    private static DriveItem folder(String id, String parent, String name) {
        return new DriveItem(id, name, DriveApiClient.FOLDER_MIME, List.of(parent), false, null, CREATED, null,
            List.of(LEADER_EMAIL));
    }
}
