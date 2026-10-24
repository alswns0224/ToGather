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
import com.togather.integration.service.IntegrationService.TargetAccess;
import com.togather.sync.service.SyncAdapter.SyncContext;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
        // 데이터베이스를 연동 대상으로 고른 연결의 토큰을 쓴다.
        when(integrationService.findTargetAccess(workspaceId, Provider.NOTION))
                .thenReturn(Optional.of(new TargetAccess(1L, userId, TOKEN, List.of("db1"))));
        when(integrationService.findAccessToken(eq(workspaceId), any(), eq(Provider.NOTION)))
                .thenReturn(Optional.of("my-own-token"));
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
        assertThat(detail.bodyEditable()).isFalse(); // 최신 블록을 확인하지 못했으면 본문 수정 막기
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
    void 바뀌지_않은_페이지는_본문을_다시_읽지_않는다() {
        notionSyncAdapter.sync(new SyncContext(workspaceId, "db1", null, null, userId));

        notionSyncAdapter.sync(new SyncContext(workspaceId, "db1", null, T0.plusHours(1), userId));

        // 처음 동기화에서 한 번씩만 읽었다. (두 번째는 수정 시각이 지나지 않아 건너뜀)
        verify(notionApiClient, org.mockito.Mockito.times(1)).getPageText(TOKEN, "p1");
        verify(notionApiClient, org.mockito.Mockito.times(1)).getPageText(TOKEN, "p2");
    }

    @Test
    void 연동하지_않은_데이터베이스에는_만들_수_없다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        notionContentService.createContent(workspaceId, userId, "제목", "본문", "other-db"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTION_DATABASE_NOT_LINKED));
    }

    @Test
    void 이미지나_하위_블록이_있는_문서는_본문을_수정할_수_없다() {
        notionSyncAdapter.sync(new SyncContext(workspaceId, "db1", null, null, userId));
        Long id = notionContentService.getPages(workspaceId).getFirst().contentRefId();
        when(notionApiClient.getPage(TOKEN, "p1")).thenReturn(page("p1", "회의록", T0));
        when(notionApiClient.getPageContent(TOKEN, "p1"))
                .thenReturn(new NotionApiClient.PageContent("# 안건", false));

        assertThat(notionContentService.getPage(workspaceId, userId, id).bodyEditable()).isFalse();

        when(notionApiClient.getPageContent(TOKEN, "p1")).thenReturn(new NotionApiClient.PageContent("# 안건", true));
        assertThat(notionContentService.getPage(workspaceId, userId, id).bodyEditable()).isTrue();
    }

    @Test
    void 연동_대상을_고른_연결이_없으면_요청자_토큰을_쓴다() {
        when(integrationService.findTargetAccess(workspaceId, Provider.NOTION)).thenReturn(Optional.empty());
        when(notionApiClient.queryDatabase(eq("my-own-token"), eq("db1"), anyInt())).thenReturn(List.of());

        notionSyncAdapter.sync(new SyncContext(workspaceId, "db1", null, null, userId));

        verify(notionApiClient).queryDatabase(eq("my-own-token"), eq("db1"), anyInt());
    }

    @Test
    void 페이지를_연동하면_하위_페이지와_데이터베이스_행까지_가져온다() {
        usePageTarget();

        notionSyncAdapter.sync(new SyncContext(workspaceId, PAGE_TARGET, null, null, userId));

        assertThat(notionContentService.getPages(workspaceId)).extracting(NotionPageResponse::title)
                .containsExactlyInAnyOrder("카페를 만들어보자", "카페 기능", "menu_id");
        // 모두 연동 대상(페이지)에 묶여 저장된다.
        assertThat(notionContentService.getPages(workspaceId)).extracting(NotionPageResponse::databaseId)
                .containsOnly(PAGE_TARGET);
    }

    @Test
    void 페이지_아래에서_사라진_하위_페이지는_원본_삭제로_표시한다() {
        usePageTarget();
        notionSyncAdapter.sync(new SyncContext(workspaceId, PAGE_TARGET, null, null, userId));
        when(notionApiClient.crawlPage(eq(TOKEN), eq("root"), anyInt(), anyInt())).thenReturn(
                new NotionApiClient.PageTree(List.of(plainPage("root", "카페를 만들어보자")), false));

        notionSyncAdapter.sync(new SyncContext(workspaceId, PAGE_TARGET, null, T0.plusHours(1), userId));

        assertThat(notionContentService.getPages(workspaceId)).extracting(NotionPageResponse::title)
                .containsExactly("카페를 만들어보자");
    }

    @Test
    void 페이지를_다_가져오지_못했으면_사라진_것으로_보지_않는다() {
        usePageTarget();
        notionSyncAdapter.sync(new SyncContext(workspaceId, PAGE_TARGET, null, null, userId));
        when(notionApiClient.crawlPage(eq(TOKEN), eq("root"), anyInt(), anyInt())).thenReturn(
                new NotionApiClient.PageTree(List.of(plainPage("root", "카페를 만들어보자")), true));

        notionSyncAdapter.sync(new SyncContext(workspaceId, PAGE_TARGET, null, T0.plusHours(1), userId));

        assertThat(notionContentService.getPages(workspaceId)).hasSize(3);
    }

    @Test
    void 새_문서를_만들_곳은_데이터베이스_다음에_페이지가_나온다() {
        usePageTarget();
        when(notionApiClient.getPage(TOKEN, "root")).thenReturn(plainPage("root", "카페를 만들어보자"));

        assertThat(notionContentService.getDatabases(workspaceId, userId))
                .extracting(r -> r.databaseId()).containsExactly("db1", PAGE_TARGET);
    }

    @Test
    void 페이지만_연동했으면_그_페이지_아래에_문서를_만든다() {
        when(integrationService.getConnectionTargets(workspaceId, Provider.NOTION)).thenReturn(List.of(PAGE_TARGET));
        when(integrationService.findTargetAccess(workspaceId, Provider.NOTION))
                .thenReturn(Optional.of(new TargetAccess(1L, userId, TOKEN, List.of(PAGE_TARGET))));
        when(notionApiClient.createChildPage(TOKEN, "root", "결정사항", "본문"))
                .thenReturn(plainPage("new", "결정사항"));

        // 논의 정리 결정 문서처럼 위치를 정하지 않고 만든다.
        ContentRefDTO created = notionContentService.createContent(workspaceId, userId, "결정사항", "본문", null);

        verify(notionApiClient).createChildPage(TOKEN, "root", "결정사항", "본문");
        assertThat(created.title()).isEqualTo("결정사항");
        assertThat(notionContentService.getPages(workspaceId)).extracting(NotionPageResponse::databaseId)
                .containsExactly(PAGE_TARGET);
    }

    @Test
    void 데이터베이스와_페이지가_둘_다_있으면_데이터베이스에_만든다() {
        usePageTarget();
        when(notionApiClient.createPage(TOKEN, DB, "결정사항", "본문")).thenReturn(page("p9", "결정사항", T0));

        notionContentService.createContent(workspaceId, userId, "결정사항", "본문", null);

        verify(notionApiClient).createPage(TOKEN, DB, "결정사항", "본문");
    }

    @Test
    void 연동한_곳이_하나도_없으면_만들_수_없다() {
        when(integrationService.getConnectionTargets(workspaceId, Provider.NOTION)).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        notionContentService.createContent(workspaceId, userId, "제목", "본문", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTION_DATABASE_NOT_LINKED));
    }

    @Test
    void 일반_페이지의_제목은_title_속성으로_수정한다() {
        usePageTarget();
        notionSyncAdapter.sync(new SyncContext(workspaceId, PAGE_TARGET, null, null, userId));
        Long id = notionContentService.getPages(workspaceId).stream()
                .filter(p -> p.title().equals("카페 기능")).findFirst().orElseThrow().contentRefId();
        when(notionApiClient.getPage(TOKEN, "child")).thenReturn(plainPage("child", "카페 기능"));
        when(notionApiClient.updatePage(TOKEN, "child", "title", "카페 기능 정리", null))
                .thenReturn(plainPage("child", "카페 기능 정리"));

        ContentRefDTO updated = notionContentService.updateContent(workspaceId, userId, id, "카페 기능 정리", null);

        assertThat(updated.title()).isEqualTo("카페 기능 정리");
    }

    /** "카페를 만들어보자" 페이지를 연동 대상으로 고른 상태: 하위 페이지 1개, 페이지 안 데이터베이스 행 1개 */
    private void usePageTarget() {
        when(integrationService.getConnectionTargets(workspaceId, Provider.NOTION))
                .thenReturn(List.of("db1", PAGE_TARGET));
        when(integrationService.findTargetAccess(workspaceId, Provider.NOTION))
                .thenReturn(Optional.of(new TargetAccess(1L, userId, TOKEN, List.of("db1", PAGE_TARGET))));
        when(notionApiClient.crawlPage(eq(TOKEN), eq("root"), anyInt(), anyInt())).thenReturn(
                new NotionApiClient.PageTree(List.of(plainPage("root", "카페를 만들어보자"),
                        plainPage("child", "카페 기능"),
                        new NotionPage("row", "inline-db", "menu_id", "https://notion.so/row", T0, T0, false)),
                        false));
        when(notionApiClient.getPageText(eq(TOKEN), any())).thenReturn("본문");
    }

    private static final String PAGE_TARGET = "page:root";

    private static NotionPage plainPage(String id, String title) {
        return new NotionPage(id, null, title, "https://notion.so/" + id, T0.minusDays(1), T0, false);
    }

    private static NotionPage page(String id, String title, LocalDateTime edited) {
        return new NotionPage(id, "db1", title, "https://notion.so/" + id, edited.minusDays(3), edited, false);
    }
}
