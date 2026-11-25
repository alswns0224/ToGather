package com.togather.content.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.access.ContentAccessService;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveFile;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.TeamTokenResolver;
import com.togather.integration.service.IntegrationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DriveFolderHierarchyTest {
    @Mock ContentRefRepository refs;
    @Mock ContentRefService contents;
    @Mock IntegrationService integrations;
    @Mock ContentAccessService access;
    @Mock WorkspaceAccessChecker membership;
    @Mock DriveApiClient drive;
    @Mock TeamTokenResolver teamTokens;
    @Mock WorkspaceEventService events;
    @InjectMocks DriveContentService service;
    private static final Provider PROVIDER = Provider.GOOGLE_DRIVE;

    private void selected(String... ids) {
        when(integrations.getConnectionTargets(1L, PROVIDER)).thenReturn(List.of(ids));
        when(access.visibleTargets(1L, 2L, PROVIDER)).thenReturn(Set.of(ids));
        Map<String, String> names = new LinkedHashMap<>();
        for (String id : ids) names.put(id, "saved-" + id);
        when(integrations.getConnectionTargetNames(1L, PROVIDER)).thenReturn(names);
        if (ids.length > 0) when(integrations.getAccessToken(1L, 2L, PROVIDER)).thenReturn("own-token");
    }

    private void folder(String id, String name, String... parents) {
        when(drive.getFileMetadata("own-token", id)).thenReturn(new DriveFile(id, name,
                "application/vnd.google-apps.folder", null, null, null, null, List.of(parents), false));
    }

    @Test void selectedChildRetainsItsRealParentAndLatestName() {
        selected("analysis", "test1");
        folder("analysis", "분석");
        folder("test1", "테스트1", "analysis");
        var result = service.getFolderHierarchy(1L, 2L);
        assertThat(result.getFirst().ancestors()).isEmpty();
        assertThat(result.get(1).name()).isEqualTo("테스트1");
        assertThat(result.get(1).ancestors()).containsExactly(new DriveContentService.DriveFolderResponse("analysis", "분석"));
        verify(drive, times(1)).getFileMetadata("own-token", "analysis");
        verify(teamTokens, never()).tokenFor(any(), any(), any(), any());
    }

    @Test void unselectedIntermediateFoldersRemainInTheRealPath() {
        selected("root", "leaf");
        folder("root", "프로젝트");
        folder("middle", "디자인", "root");
        folder("leaf", "최종 시안", "middle");
        assertThat(service.getFolderHierarchy(1L, 2L).get(1).ancestors())
                .extracting(DriveContentService.DriveFolderResponse::folderId).containsExactly("root", "middle");
    }

    @Test void ancestorsOutsideTheIntegratedScopeAreNotReturned() {
        selected("root", "child");
        folder("outside", "개인 상위 폴더");
        folder("root", "프로젝트", "outside");
        folder("child", "자료", "root");
        var result = service.getFolderHierarchy(1L, 2L);
        assertThat(result.getFirst().ancestors()).isEmpty();
        assertThat(result.get(1).ancestors()).extracting(DriveContentService.DriveFolderResponse::folderId).containsExactly("root");
    }

    @Test void inaccessibleParentDoesNotHideAnAccessibleChild() {
        selected("child");
        folder("child", "공유받은 폴더", "hidden");
        when(drive.getFileMetadata("own-token", "hidden")).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(service.getFolderHierarchy(1L, 2L).getFirst().ancestors()).isEmpty();
    }

    @Test void cyclicParentsCannotCreateAnEndlessOrRootlessMenu() {
        selected("one", "two");
        folder("one", "하나", "two");
        folder("two", "둘", "one");
        assertThat(service.getFolderHierarchy(1L, 2L)).allMatch(folder -> folder.ancestors().isEmpty());
    }

    @Test void upstreamFailureIsNotReportedAsAFlatSuccessfulHierarchy() {
        selected("root");
        when(drive.getFileMetadata("own-token", "root")).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));
        assertThatThrownBy(() -> service.getFolderHierarchy(1L, 2L)).isInstanceOf(BusinessException.class);
    }

    @Test void emptySelectionDoesNotRequestAnyTokenOrDriveMetadata() {
        selected();
        assertThat(service.getFolderHierarchy(1L, 2L)).isEmpty();
        verify(integrations, never()).getAccessToken(any(), any(), any());
        verify(drive, never()).getFileMetadata(anyString(), anyString());
    }

    @Test void hiddenSelectedFoldersAreExcludedBeforeMetadataLookup() {
        selected("root", "hidden");
        when(access.visibleTargets(1L, 2L, PROVIDER)).thenReturn(Set.of("root"));
        folder("root", "프로젝트");
        assertThat(service.getFolderHierarchy(1L, 2L)).extracting(DriveContentService.DriveFolderHierarchyResponse::folderId)
                .containsExactly("root");
        verify(drive, never()).getFileMetadata("own-token", "hidden");
    }

    @Test void missingOwnConnectionDoesNotBorrowAnotherMembersToken() {
        doThrow(new BusinessException(ErrorCode.OWN_CONNECTION_REQUIRED)).when(access).requireOwnConnection(1L, 2L, PROVIDER);
        assertThatThrownBy(() -> service.getFolderHierarchy(1L, 2L)).isInstanceOf(BusinessException.class);
        verify(integrations, never()).getAccessToken(any(), any(), any());
        verify(teamTokens, never()).tokenFor(any(), any(), any(), any());
    }

    @Test void nonMemberCannotRequestAnyHierarchyOrToken() {
        doThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED)).when(membership).checkMember(1L, 2L);
        assertThatThrownBy(() -> service.getFolderHierarchy(1L, 2L)).isInstanceOf(BusinessException.class);
        verify(integrations, never()).getAccessToken(any(), any(), any());
        verify(drive, never()).getFileMetadata(anyString(), anyString());
    }
}
