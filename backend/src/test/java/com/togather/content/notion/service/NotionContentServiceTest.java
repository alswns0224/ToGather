package com.togather.content.notion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.client.NotionApiClient;
import com.togather.content.notion.client.NotionApiClient.NotionDatabase;
import com.togather.content.notion.client.NotionApiClient.NotionPage;
import com.togather.content.notion.dto.NotionPageDetailResponse;
import com.togather.content.notion.dto.NotionPageResponse;
import com.togather.integration.service.IntegrationService;
import com.togather.sync.service.SyncAdapter.SyncContext;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class NotionContentServiceTest extends IntegrationTestSupport {

    private static final String TOKEN = "notion-token";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 7, 10, 0);
    private static final NotionDatabase DB = new NotionDatabase("db1", "팀 문서", "이름");

    @MockitoBean
    private NotionApiClient notionApiClient;
    @MockitoBean
    private IntegrationService integrationService;

    @Autowired
    private NotionSyncAdapter notionSyncAdapter;
    @Autowired
    private NotionContentService notionContentService;

    private Long userId;
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        userId = createUser("철준", "cj@test.com");
        workspaceId = createWorkspace(userId);
        when(integrationService.getConnectionTargets(workspaceId, Provider.NOTION)).thenReturn(List.of("db1"));
        when(integrationService.getAccessToken(eq(workspaceId), any(), eq(Provider.NOTION))).thenReturn(TOKEN);
        when(notionApiClient.getDatabase(TOKEN, "db1")).thenReturn(DB);
        when(notionApiClient.queryDatabase(eq(TOKEN), eq("db1"), anyInt()))
                .thenReturn(List.of(page("p1", "회의록", T0), page("p2", "기획서", T0.minusDays(1))));
        when(notionApiClient.getPageText(TOKEN, "p1")).thenReturn("# 안건\n- 발표");
        when(notionApiClient.getPageText(TOKEN, "p2")).thenReturn("기획 내용");
    }

    @Test
    void 동기화하면_페이지를_최근_수정순으로_보여준다() {
        notionSyncAdapter.sync(new SyncContext(workspaceId, "db1", null, null, userId));

        List<NotionPageResponse> pages = notionContentService.getPages(workspaceId);

        assertThat(pages).extracting(NotionPageResponse::title).containsExactly("회의록", "기획서");
        assertThat(pages.getFirst().fromAnalysis()).isFalse();
    }

    @Test
    void 목록에서_사라진_페이지는_원본_삭제로_표시한다() {
        notionSyncAdapter.sync(new SyncContext(workspaceId, "db1", null, null, userId));
        when(notionApiClient.queryDatabase(eq(TOKEN), eq("db1"), anyInt())).thenReturn(List.of(page("p1", "회의록", T0)));

        notionSyncAdapter.sync(new SyncContext(workspaceId, "db1", null, T0.plusHours(1), userId));

        assertThat(notionContentService.getPages(workspaceId)).extracting(NotionPageResponse::title)
                .containsExactly("회의록");
    }

    @Test
    void 문서를_열_때_Notion이_실패하면_저장된_본문을_보여준다() {
        notionSyncAdapter.sync(new SyncContext(workspaceId, "db1", null, null, userId));
        Long id = notionContentService.getPages(workspaceId).getFirst().contentRefId();
        when(notionApiClient.getPage(TOKEN, "p1")).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));

        NotionPageDetailResponse detail = notionContentService.getPage(workspaceId, userId, id);

        assertThat(detail.refreshFailed()).isTrue();
        assertThat(detail.body()).isEqualTo("# 안건\n- 발표");
    }

    @Test
    void 문서를_만들고_수정하고_삭제한다() {
        when(notionApiClient.createPage(TOKEN, DB, "새 문서", "본문")).thenReturn(page("p3", "새 문서", T0));
        ContentRefDTO created = notionContentService.createContent(workspaceId, userId, "새 문서", "본문", null);
        assertThat(created.content()).isEqualTo("본문");

        when(notionApiClient.updatePage(TOKEN, "p3", "이름", null, "수정된 본문"))
                .thenReturn(page("p3", "새 문서", T0.plusHours(1)));
        ContentRefDTO updated = notionContentService.updateContent(workspaceId, userId, created.contentRefId(), null,
                "수정된 본문");
        assertThat(updated.content()).isEqualTo("수정된 본문");
        assertThat(updated.lastModifiedAt()).isEqualTo(T0.plusHours(1));

        notionContentService.deleteContent(workspaceId, userId, created.contentRefId());
        verify(notionApiClient).archivePage(TOKEN, "p3");
        assertThat(notionContentService.getPages(workspaceId)).isEmpty();
        assertThat(notionContentService.getContentDetail(created.contentRefId()).isOriginDeleted()).isTrue();
    }

    @Test
    void 연동하지_않은_데이터베이스에는_만들_수_없다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        notionContentService.createContent(workspaceId, userId, "제목", "본문", "other-db"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTION_DATABASE_NOT_LINKED));
    }

    private static NotionPage page(String id, String title, LocalDateTime edited) {
        return new NotionPage(id, "db1", title, "https://notion.so/" + id, edited.minusDays(3), edited, false);
    }
}
