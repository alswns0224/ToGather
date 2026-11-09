package com.togather.content.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.domain.ContentRef.DriveFileState;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.ChangeResult;
import com.togather.content.drive.client.DriveApiClient.DriveChange;
import com.togather.content.drive.client.DriveApiClient.DriveFile;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.service.TeamTokenResolver;
import com.togather.integration.service.IntegrationService;
import com.togather.support.IntegrationTestSupport;
import com.togather.sync.service.SyncAdapter.SyncContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class DriveSyncAdapterTest extends IntegrationTestSupport {

    private static final Provider DRIVE = Provider.GOOGLE_DRIVE;
    private static final String SOURCE = "folder-a";
    private static final String DESTINATION = "folder-b";
    private static final String OUTSIDE = "outside";
    private static final String TOKEN = "drive-token";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 7, 10, 0);

    @Autowired
    private DriveSyncAdapter adapter;
    @Autowired
    private DriveContentService driveContentService;
    @Autowired
    private ContentRefService contentRefService;
    @Autowired
    private ContentRefRepository contentRefRepository;
    @MockitoBean
    private DriveApiClient driveApiClient;
    @MockitoBean
    private IntegrationService integrationService;
    @MockitoBean
    private TeamTokenResolver teamTokenResolver;

    private Long userId;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        userId = createUser("Drive Test", "drive@test.com");
        workspaceId = createWorkspace(userId);
        when(integrationService.getConnectionTargets(workspaceId, DRIVE)).thenReturn(List.of(SOURCE, DESTINATION));
        when(teamTokenResolver.tokenFor(eq(workspaceId), eq(DRIVE), anyString(), eq(userId))).thenReturn(TOKEN);
    }

    @Test
    void 폴더_이동은_같은_참조를_유지하고_위치_변경을_한_번_집계한다() {
        Long refId = save("moved");
        changes(new DriveChange("moved", false, file("moved", DESTINATION, false)));

        assertThat(ContentRefService.countChanges(() -> sync(DESTINATION, "cursor"))).isEqualTo(1);
        contentRefService.updateDriveLocation(workspaceId, "moved", SOURCE, SOURCE, DriveFileState.UNAVAILABLE);
        assertThat(ContentRefService.countChanges(() -> sync(SOURCE, "cursor"))).isZero();
        assertThat(stored(refId).getChannelId()).isEqualTo(DESTINATION);
        assertThat(driveContentService.getFiles(workspaceId, userId, SOURCE)).isEmpty();
        assertThat(driveContentService.getFiles(workspaceId, userId, DESTINATION))
                .extracting(ContentRefDTO::contentRefId).containsExactly(refId);
    }

    @Test
    void 연동_폴더_밖으로_이동하면_숨기고_돌아오면_같은_참조로_표시한다() {
        Long refId = save("moved");
        changes(new DriveChange("moved", false, file("moved", OUTSIDE, false)));

        sync(SOURCE, "cursor");

        assertThat(stored(refId).getChannelId()).isEqualTo(OUTSIDE);
        assertThat(stored(refId).isOriginDeleted()).isFalse();
        assertThat(driveContentService.getFiles(workspaceId, userId, null)).isEmpty();
        assertThat(driveContentService.getTrashFiles(workspaceId, userId)).isEmpty();

        changes(new DriveChange("moved", false, file("moved", DESTINATION, false)));
        sync(DESTINATION, "cursor");
        assertThat(driveContentService.getFiles(workspaceId, userId, DESTINATION))
                .extracting(ContentRefDTO::contentRefId).containsExactly(refId);
    }

    @Test
    void 휴지통에_있는_파일만_복원_목록에_표시한다() {
        Long trashedId = save("trashed");
        Long unavailableId = save("unavailable");
        when(driveApiClient.getFileMetadata(TOKEN, "unavailable"))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        changes(new DriveChange("trashed", false, file("trashed", SOURCE, true)),
                new DriveChange("unavailable", true, null));

        sync(SOURCE, "cursor");

        assertThat(stored(trashedId).isDriveTrashed()).isTrue();
        assertThat(stored(unavailableId).isOriginDeleted()).isTrue();
        assertThat(driveContentService.getTrashFiles(workspaceId, userId))
                .extracting(ContentRefDTO::contentRefId).containsExactly(trashedId);

        changes(new DriveChange("trashed", false, file("trashed", SOURCE, false)));
        sync(SOURCE, "cursor");
        assertThat(stored(trashedId).isOriginDeleted()).isFalse();
        assertThat(driveContentService.getTrashFiles(workspaceId, userId)).isEmpty();
    }

    @Test
    void 전체_동기화는_이전_삭제_표시를_실제_휴지통과_폴더_이동으로_구분한다() {
        Long movedId = save("moved");
        Long trashedId = save("trashed");
        contentRefService.markOriginDeleted(movedId);
        contentRefService.markOriginDeleted(trashedId);
        when(driveApiClient.getStartPageToken(TOKEN)).thenReturn("new-cursor");
        when(driveApiClient.listTrashedFolderFiles(TOKEN, SOURCE)).thenReturn(List.of(file("trashed", SOURCE, true)));
        when(driveApiClient.getFileMetadata(TOKEN, "moved")).thenReturn(file("moved", OUTSIDE, false));

        sync(SOURCE, null);

        assertThat(stored(movedId).getChannelId()).isEqualTo(OUTSIDE);
        assertThat(stored(movedId).isOriginDeleted()).isFalse();
        assertThat(driveContentService.getTrashFiles(workspaceId, userId))
                .extracting(ContentRefDTO::contentRefId).containsExactly(trashedId);
    }

    @Test
    void 파일_조회가_일시적으로_실패하면_원본_삭제로_처리하지_않는다() {
        Long refId = save("retry");
        when(driveApiClient.getFileMetadata(TOKEN, "retry"))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));
        changes(new DriveChange("retry", true, null));

        assertThatThrownBy(() -> sync(SOURCE, "cursor")).isInstanceOf(BusinessException.class);
        assertThat(stored(refId).isOriginDeleted()).isFalse();
    }

    private Long save(String fileId) {
        return contentRefService.upsert(new ContentRefUpsert(
                workspaceId, null, DRIVE, ContentType.FILE, fileId, SOURCE, null,
                fileId, "파일 유형: text/plain", "https://drive.google.com/file/d/" + fileId + "/view",
                CREATED_AT)).contentRefId();
    }

    private void changes(DriveChange... changes) {
        when(driveApiClient.listChanges(TOKEN, "cursor"))
                .thenReturn(new ChangeResult(List.of(changes), "next-cursor"));
    }

    private void sync(String folderId, String cursor) {
        adapter.sync(new SyncContext(workspaceId, folderId, null, null, cursor, userId));
    }

    private ContentRef stored(Long refId) {
        return contentRefRepository.findById(refId).orElseThrow();
    }

    private DriveFile file(String fileId, String parentId, boolean trashed) {
        return new DriveFile(fileId, fileId, "text/plain", null, null, CREATED_AT, CREATED_AT,
                List.of(parentId), trashed);
    }
}
