package com.togather.content.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveFile;
import com.togather.content.drive.client.DriveApiClient.DriveItem;
import com.togather.content.drive.dto.DriveTrashItem;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.drive.dto.DriveUploadMode;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.service.TeamTokenResolver;
import com.togather.integration.client.GoogleIntegrationClient;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.OwnAccess;
import com.togather.support.IntegrationTestSupport;
import com.togather.workspace.service.WorkspaceMemberService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 팀장 단일 소유: 휴지통 폴더, 팀장 토큰 업로드, 소유권 정리, 팀장 넘기기 인계 (Drive API는 mock) */
class DriveLeaderOwnershipTest extends IntegrationTestSupport {

    private static final Provider DRIVE = Provider.GOOGLE_DRIVE;
    private static final String ROOT = "root-folder";
    private static final String SUB = "sub-folder";
    private static final String TRASH = "trash-folder";
    private static final String MY_DRIVE = "my-drive-root";
    private static final String LEADER_TOKEN = "leader-token";
    private static final String MEMBER_TOKEN = "member-token";
    private static final String LEADER_EMAIL = "leader@test.example";
    private static final String MEMBER_EMAIL = "member@test.example";
    private static final String FOLDER_MIME = DriveApiClient.FOLDER_MIME;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 25, 10, 0);

    @Autowired private DriveTrashService trash;
    @Autowired private DriveOwnershipService ownership;
    @Autowired private DriveOwnershipGuard guard;
    @Autowired private DriveOwnerAccess ownerAccess;
    @Autowired private DriveContentService drive;
    @Autowired private ContentRefService contents;
    @Autowired private WorkspaceMemberService members;
    @MockitoBean private DriveApiClient api;
    @MockitoBean private IntegrationService integration;
    @MockitoBean private TeamTokenResolver teamTokenResolver;
    @TempDir Path temp;

    private Long leader;
    private Long member;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        leader = createUser("팀장", UUID.randomUUID() + "@test.example");
        member = createUser("팀원", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(leader);
        addMember(workspaceId, member, "MEMBER");

        when(integration.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(ROOT));
        when(integration.findOwnAccess(workspaceId, leader, DRIVE))
            .thenReturn(Optional.of(new OwnAccess(1L, LEADER_EMAIL, "drive", LEADER_TOKEN)));
        when(integration.findOwnAccess(workspaceId, member, DRIVE))
            .thenReturn(Optional.of(new OwnAccess(2L, MEMBER_EMAIL, "drive", MEMBER_TOKEN)));
        when(integration.findOwnExternalAccountId(workspaceId, leader, DRIVE)).thenReturn(Optional.of(LEADER_EMAIL));
        when(integration.findOwnExternalAccountId(workspaceId, member, DRIVE)).thenReturn(Optional.of(MEMBER_EMAIL));
        when(integration.hasConnection(eq(workspaceId), any(), eq(DRIVE))).thenReturn(true);

        when(api.createFolder(eq(LEADER_TOKEN), anyString(), isNull(), any())).thenReturn(item(TRASH, MY_DRIVE, true, LEADER_EMAIL));
        when(api.getItem(LEADER_TOKEN, TRASH)).thenReturn(item(TRASH, MY_DRIVE, true, LEADER_EMAIL));
        when(api.getItem(LEADER_TOKEN, MY_DRIVE)).thenReturn(new DriveItem(MY_DRIVE, "내 드라이브", FOLDER_MIME,
            List.of(), false, null, null, null, List.of(LEADER_EMAIL)));
        when(api.getItem(LEADER_TOKEN, ROOT)).thenReturn(item(ROOT, MY_DRIVE, true, LEADER_EMAIL));
        when(api.getItem(LEADER_TOKEN, SUB)).thenReturn(item(SUB, ROOT, true, LEADER_EMAIL));
    }

    @Test
    void 팀원이_보낸_파일은_팀장_토큰으로_휴지통_폴더에_옮기고_복원한다() {
        track("file-1");
        when(api.getItem(LEADER_TOKEN, "file-1")).thenReturn(item("file-1", ROOT, false, LEADER_EMAIL));

        trash.trash(workspaceId, member, "file-1");

        verify(api).moveItem(LEADER_TOKEN, "file-1", ROOT, TRASH);
        assertThat(drive.getFiles(workspaceId, member, null)).isEmpty();

        when(api.listChildren(LEADER_TOKEN, TRASH)).thenReturn(List.of(item("file-1", TRASH, false, LEADER_EMAIL)));
        List<DriveTrashItem> items = trash.list(workspaceId, member);
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.itemId()).isEqualTo("file-1");
            assertThat(item.trashedBy()).isEqualTo(member);
            assertThat(item.trashedByName()).isEqualTo("팀원");
            assertThat(item.contentRefId()).isNotNull();
            assertThat(item.source()).isEqualTo(DriveTrashItem.TOGATHER);
        });

        when(api.getItem(LEADER_TOKEN, "file-1")).thenReturn(item("file-1", TRASH, false, LEADER_EMAIL));
        trash.restore(workspaceId, member, "file-1");

        verify(api).moveItem(LEADER_TOKEN, "file-1", TRASH, ROOT);
        assertThat(drive.getFiles(workspaceId, member, null)).extracting(ContentRefDTO::externalId)
            .containsExactly("file-1");
    }

    @Test
    void 휴지통_폴더는_처음_한_번만_만든다() {
        when(api.getItem(LEADER_TOKEN, "a")).thenReturn(item("a", ROOT, false, LEADER_EMAIL));
        when(api.getItem(LEADER_TOKEN, "b")).thenReturn(item("b", ROOT, false, LEADER_EMAIL));

        trash.trash(workspaceId, leader, "a");
        trash.trash(workspaceId, leader, "b");

        verify(api, times(1)).createFolder(eq(LEADER_TOKEN), anyString(), isNull(), any());
        assertThat(ownerAccess.trashFolderId(workspaceId)).contains(TRASH);
    }

    @Test
    void 휴지통_폴더는_워크스페이스_번호가_든_이름과_ToGather_표시로_만든다() {
        when(api.getItem(LEADER_TOKEN, "a")).thenReturn(item("a", ROOT, false, LEADER_EMAIL));

        trash.trash(workspaceId, leader, "a");

        verify(api).createFolder(LEADER_TOKEN, "ToGather 휴지통 · 테스트 #" + workspaceId, null, Map.of(
            GoogleIntegrationClient.DRIVE_TRASH_MARKER, "true",
            GoogleIntegrationClient.DRIVE_TRASH_WORKSPACE, workspaceId.toString()));
    }

    @Test
    void 저장한_휴지통_ID를_잃어도_표시로_찾아_다시_쓴다() {
        when(api.findMarkedFolders(LEADER_TOKEN, GoogleIntegrationClient.DRIVE_TRASH_WORKSPACE, workspaceId.toString()))
            .thenReturn(List.of(item("old-trash", MY_DRIVE, true, LEADER_EMAIL)));
        when(api.getItem(LEADER_TOKEN, "a")).thenReturn(item("a", ROOT, false, LEADER_EMAIL));

        trash.trash(workspaceId, leader, "a");

        verify(api, never()).createFolder(anyString(), anyString(), any(), any());
        verify(api).moveItem(LEADER_TOKEN, "a", ROOT, "old-trash");
        verify(api).updateFolder(eq(LEADER_TOKEN), eq("old-trash"), eq("ToGather 휴지통 · 테스트 #" + workspaceId), any());
        assertThat(ownerAccess.trashFolderId(workspaceId)).contains("old-trash");
    }

    @Test
    void 원래_폴더가_휴지통에_있으면_연동_폴더로_복원한다() {
        when(api.getItem(LEADER_TOKEN, "file-2")).thenReturn(item("file-2", SUB, false, LEADER_EMAIL));
        trash.trash(workspaceId, member, "file-2");
        trash.trash(workspaceId, member, SUB);
        verify(api).moveItem(LEADER_TOKEN, "file-2", SUB, TRASH);
        verify(api).moveItem(LEADER_TOKEN, SUB, ROOT, TRASH);

        when(api.getItem(LEADER_TOKEN, SUB)).thenReturn(item(SUB, TRASH, true, LEADER_EMAIL));
        when(api.getItem(LEADER_TOKEN, "file-2")).thenReturn(item("file-2", TRASH, false, LEADER_EMAIL));
        trash.restore(workspaceId, member, "file-2");

        verify(api).moveItem(LEADER_TOKEN, "file-2", TRASH, ROOT);
    }

    @Test
    void 연동_폴더_자체는_휴지통으로_옮길_수_없다() {
        assertThatThrownBy(() -> trash.trash(workspaceId, leader, ROOT))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(api, never()).moveItem(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 영구_삭제는_팀장_관리자만_한다() {
        track("file-3");
        when(api.getItem(LEADER_TOKEN, "file-3")).thenReturn(item("file-3", ROOT, false, LEADER_EMAIL));
        trash.trash(workspaceId, member, "file-3");
        when(api.getItem(LEADER_TOKEN, "file-3")).thenReturn(item("file-3", TRASH, false, LEADER_EMAIL));

        assertThatThrownBy(() -> trash.deleteForever(workspaceId, member, "file-3"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.WORKSPACE_OWNER_REQUIRED);

        trash.deleteForever(workspaceId, leader, "file-3");

        verify(api).deleteItem(LEADER_TOKEN, "file-3");
        when(api.listChildren(LEADER_TOKEN, TRASH)).thenReturn(List.of());
        assertThat(trash.list(workspaceId, leader)).isEmpty();
        assertThat(drive.getFiles(workspaceId, leader, null)).isEmpty();
    }

    @Test
    void 팀장이_Drive를_연동하지_않으면_휴지통을_쓸_수_없다() {
        when(integration.findOwnAccess(workspaceId, leader, DRIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trash.trash(workspaceId, member, "file-x"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.DRIVE_OWNER_NOT_CONNECTED);
    }

    @Test
    void 팀원이_하위_폴더에_올려도_팀장_토큰으로_올린다() throws Exception {
        Path payload = Files.writeString(temp.resolve("note.txt"), "hello");
        when(api.uploadFile(eq(LEADER_TOKEN), eq(SUB), eq("note.txt"), any(), any(Path.class)))
            .thenReturn(new DriveFile("up-1", "note.txt", "text/plain", null, null, CREATED, CREATED, List.of(SUB), false));

        ContentRefDTO uploaded = drive.uploadFile(workspaceId, member, SUB, "note.txt", "text/plain", payload,
            DriveUploadMode.CREATE_NEW, null, null);

        assertThat(uploaded.externalId()).isEqualTo("up-1");
        assertThat(uploaded.contentRefId()).isNull();
        verify(api, never()).uploadFile(eq(MEMBER_TOKEN), anyString(), anyString(), any(), any(Path.class));
    }

    @Test
    void 팀원이_Drive_웹에서_올린_파일은_팀장에게_소유권을_넘긴다() {
        when(api.listChildren(LEADER_TOKEN, ROOT)).thenReturn(List.of(item("member-file", ROOT, false, MEMBER_EMAIL)));
        when(api.ensureWriter(MEMBER_TOKEN, "member-file", LEADER_EMAIL)).thenReturn("perm-leader");

        DriveOwnershipService.PassResult result = ownership.consolidate(workspaceId);

        assertThat(result.transferred()).isEqualTo(1);
        assertThat(result.remaining()).isZero();
        verify(api).requestOwnership(MEMBER_TOKEN, "member-file", "perm-leader");
        verify(api).acceptOwnership(LEADER_TOKEN, "member-file", "perm-leader");
        assertThat(ownership.status(workspaceId, leader).pendingOwnershipCount()).isZero();
    }

    @Test
    void 새_팀장이_Drive를_연동하지_않았으면_팀장을_넘길_수_없다() {
        when(integration.hasConnection(workspaceId, member, DRIVE)).thenReturn(false);

        assertThatThrownBy(() -> members.transferOwner(workspaceId, leader, member))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.DRIVE_NEW_OWNER_NOT_CONNECTED);
        assertThat(role(leader)).isEqualTo("OWNER");
    }

    @Test
    void 팀장을_넘기면_이전_팀장_소유_항목을_새_팀장에게_넘기고_소유자를_바꾼다() {
        members.transferOwner(workspaceId, leader, member);

        assertThat(role(member)).isEqualTo("OWNER");
        assertThat(ownerAccess.ownerUserId(workspaceId)).contains(leader);
        assertThatThrownBy(() -> members.removeMember(workspaceId, member, leader))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.DRIVE_OWNERSHIP_TRANSFER_IN_PROGRESS);

        when(api.listChildren(LEADER_TOKEN, ROOT)).thenReturn(List.of(item("leader-file", ROOT, false, LEADER_EMAIL)));
        when(api.ensureWriter(LEADER_TOKEN, ROOT, MEMBER_EMAIL)).thenReturn("perm-member");
        when(api.ensureWriter(LEADER_TOKEN, "leader-file", MEMBER_EMAIL)).thenReturn("perm-member");

        ownership.processRunningJobs();

        verify(api).requestOwnership(LEADER_TOKEN, ROOT, "perm-member");
        verify(api).acceptOwnership(MEMBER_TOKEN, ROOT, "perm-member");
        verify(api).requestOwnership(LEADER_TOKEN, "leader-file", "perm-member");
        verify(api).acceptOwnership(MEMBER_TOKEN, "leader-file", "perm-member");
        assertThat(ownerAccess.ownerUserId(workspaceId)).contains(member);
        assertThat(ownership.status(workspaceId, member).transfer().status()).isEqualTo("DONE");
    }

    @Test
    void 인계_중에는_두_사람의_Drive_연결을_해제할_수_없다() {
        guard.beforeDisconnect(workspaceId, leader, DRIVE);
        members.transferOwner(workspaceId, leader, member);

        assertThatThrownBy(() -> guard.beforeDisconnect(workspaceId, leader, DRIVE))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.DRIVE_OWNERSHIP_TRANSFER_IN_PROGRESS);
        assertThatThrownBy(() -> guard.beforeDisconnect(workspaceId, member, DRIVE))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.DRIVE_OWNERSHIP_TRANSFER_IN_PROGRESS);
    }

    private void track(String fileId) {
        contents.upsertDriveFile(new ContentRefUpsert(workspaceId, null, DRIVE, ContentType.FILE, fileId, ROOT, null,
            fileId + ".pdf", "파일 유형: application/pdf", "https://drive.google.com/file/d/" + fileId,
            CREATED, CREATED, null, null), 10L);
    }

    private String role(Long userId) {
        return jdbc.queryForObject("SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
            String.class, workspaceId, userId);
    }

    private static DriveItem item(String id, String parent, boolean folder, String ownerEmail) {
        return new DriveItem(id, id, folder ? FOLDER_MIME : "application/pdf", List.of(parent), false,
            folder ? null : 10L, CREATED, null, List.of(ownerEmail));
    }
}
