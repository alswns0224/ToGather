package com.togather.content.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.togather.common.enums.ConnectionStatus;
import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.client.NotionApiClient;
import com.togather.content.notion.dto.NotionPageResponse;
import com.togather.content.notion.service.NotionContentService;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.integration.domain.IntegrationTarget;
import com.togather.integration.domain.ServiceConnection;
import com.togather.integration.repository.IntegrationTargetRepository;
import com.togather.integration.repository.ServiceConnectionRepository;
import com.togather.integration.service.TokenCipher;
import com.togather.search.client.EmbeddingClient;
import com.togather.search.client.VectorStore;
import com.togather.search.client.VectorStore.VectorHit;
import com.togather.search.dto.SearchResponse;
import com.togather.search.service.SearchService;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 팀원마다 원본 서비스 권한대로 팀 자료를 보여 주는지 (개인 연동 + 원본 권한).
 * 원본 확인(Slack 채널 멤버, Notion 열람, Drive 폴더 열람)은 가짜 응답으로 바꾸고, 연결·자료는 DB에 실제로 저장한다.
 */
@DisplayName("팀 자료는 원본에서 볼 수 있는 팀원에게만")
class ContentAccessServiceTest extends IntegrationTestSupport {

    @MockitoBean private SlackApiClient slack;
    @MockitoBean private NotionApiClient notion;
    @MockitoBean private DriveApiClient drive;
    @MockitoBean private EmbeddingClient embedding;
    @MockitoBean private VectorStore vectorStore;

    @Autowired private ContentAccessService access;
    @Autowired private ContentRefService contents;
    @Autowired private ContentRefRepository refs;
    @Autowired private NotionContentService notionContents;
    @Autowired private SearchService search;
    @Autowired private ServiceConnectionRepository connections;
    @Autowired private IntegrationTargetRepository targets;
    @Autowired private TokenCipher cipher;

    private Long owner;   // 채널·페이지·폴더를 연동 대상으로 고른 사람
    private Long member;  // 자기 계정으로 연동한 팀원
    private Long newbie;  // 아직 연동하지 않은 팀원
    private Long workspaceId;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(access, "enabled", true);
        owner = createUser("철준", UUID.randomUUID() + "@test.example");
        member = createUser("민준", UUID.randomUUID() + "@test.example");
        newbie = createUser("찬서", UUID.randomUUID() + "@test.example");
        workspaceId = createWorkspace(owner);
        addMember(workspaceId, member, "MEMBER");
        addMember(workspaceId, newbie, "MEMBER");

        // 팀이 고른 연동 대상: Slack 채널 2개, Notion DB 1개 + 페이지 1개, Drive 폴더 2개
        connect(owner, Provider.SLACK, "owner-slack", "C-OPEN", "C-SECRET");
        connect(owner, Provider.NOTION, "owner-notion", "db-team", "page:secret-page");
        connect(owner, Provider.GOOGLE_DRIVE, "owner-drive", "folder-team", "folder-private");

        // 민준은 자기 계정으로 세 서비스를 모두 연동했다. (대상은 고르지 않음)
        connect(member, Provider.SLACK, "member-slack");
        connect(member, Provider.NOTION, "member-notion");
        connect(member, Provider.GOOGLE_DRIVE, "member-drive");

        // 원본 권한: 민준은 C-OPEN 채널에만 있고, Notion DB만 공유받았고, Drive 팀 폴더만 열 수 있다.
        when(slack.getMemberChannelIds("member-slack")).thenReturn(Set.of("C-OPEN", "C-OTHER"));
        when(notion.getDatabase("member-notion", "db-team")).thenReturn(new NotionApiClient.NotionDatabase("db-team", "팀 DB", "Name"));
        when(notion.getPage("member-notion", "secret-page")).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(drive.getFileMetadata("member-drive", "folder-private")).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(access, "enabled", false);
    }

    @Test
    void Slack은_내가_멤버인_채널만_보인다() {
        assertThat(access.visibleTargets(workspaceId, member, Provider.SLACK))
                .as("팀이 고른 채널 중 내가 들어간 채널만 (팀이 안 고른 채널은 제외)").containsExactly("C-OPEN");
        ContentRef open = slack("C-OPEN", "1.0", "공개 채널 대화");
        ContentRef secret = slack("C-SECRET", "2.0", "비공개 채널 대화");
        assertThat(access.canView(workspaceId, member, open)).isTrue();
        assertThat(access.canView(workspaceId, member, secret)).isFalse();
        assertThatThrownBy(() -> access.requireTarget(workspaceId, member, Provider.SLACK, "C-SECRET"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONTENT_ACCESS_DENIED));
    }

    @Test
    void 연동하지_않은_팀원은_팀_자료를_볼_수_없고_연동하라는_안내를_받는다() {
        ContentRef open = slack("C-OPEN", "1.0", "공개 채널 대화");
        assertThat(access.hasOwnConnection(workspaceId, newbie, Provider.SLACK)).isFalse();
        assertThat(access.visibleTargets(workspaceId, newbie, Provider.SLACK)).isEmpty();
        assertThat(access.canView(workspaceId, newbie, open)).isFalse();
        assertThatThrownBy(() -> access.requireTarget(workspaceId, newbie, Provider.SLACK, "C-OPEN"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OWN_CONNECTION_REQUIRED));
    }

    @Test
    void Notion과_Drive도_원본에서_열_수_있는_대상만_보인다() {
        assertThat(access.visibleTargets(workspaceId, member, Provider.NOTION)).containsExactly("db-team");
        assertThat(access.visibleTargets(workspaceId, member, Provider.GOOGLE_DRIVE)).containsExactly("folder-team");
        assertThat(access.visibleTargets(workspaceId, owner, Provider.NOTION))
                .as("대상을 고른 사람은 자기 계정으로 다 열 수 있다").isNotNull();
    }

    @Test
    void Notion_문서_목록도_볼_수_있는_것만_나온다() {
        notionPage("db-team", "p1", "팀 회의록");
        notionPage("page:secret-page", "p2", "비공개 기획");
        assertThat(notionContents.getPages(workspaceId, member)).extracting(NotionPageResponse::title)
                .containsExactly("팀 회의록");
        assertThatThrownBy(() -> notionContents.getPages(workspaceId, newbie))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OWN_CONNECTION_REQUIRED));
    }

    @Test
    void 다른_팀원의_개인_자료는_섞이지_않는다() {
        ContentRef open = slack("C-OPEN", "1.0", "공개 채널 대화");
        ContentRef othersMail = refs.findById(contents.upsert(new ContentRefUpsert(workspaceId, owner, Provider.GMAIL,
                ContentType.MAIL, "mail-1", null, null, "메일", "철준의 메일", null, LocalDateTime.now()))
                .contentRefId()).orElseThrow();
        assertThat(access.filter(workspaceId, member, List.of(open, othersMail), ref -> ref)).containsExactly(open);
    }

    @Test
    void 통합_검색_결과도_볼_수_있는_채널것만_나온다() {
        ContentRef open = slack("C-OPEN", "1.0", "발표 자료는 금요일까지");
        ContentRef secret = slack("C-SECRET", "2.0", "발표 자료 비공개 논의");
        when(vectorStore.searchVectors(any(), eq(workspaceId), anyList(), anyInt())).thenReturn(List.of(
                new VectorHit(open.getContentRefId(), 0.1), new VectorHit(secret.getContentRefId(), 0.1)));
        SearchResponse result = search.search(workspaceId, member, "발표 자료", List.of(), 10);
        assertThat(result.items()).extracting(SearchResponse.Item::contentRefId)
                .contains(open.getContentRefId()).doesNotContain(secret.getContentRefId());
    }

    // ===================== 준비 =====================

    private void connect(Long userId, Provider provider, String token, String... targetIds) {
        ServiceConnection connection = new ServiceConnection(workspaceId, userId, provider, UUID.randomUUID().toString(),
                cipher.encrypt(token, "refresh"), "scope", LocalDateTime.now().plusDays(1));
        connection.changeStatus(ConnectionStatus.CONNECTED);
        connection = connections.save(connection);
        for (String target : targetIds) {
            targets.save(new IntegrationTarget(connection.getConnectionId(), target, target));
        }
    }

    private ContentRef slack(String channel, String ts, String text) {
        LocalDateTime at = LocalDateTime.now().minusHours(1);
        ContentRefDTO saved = contents.upsert(new ContentRefUpsert(workspaceId, null, Provider.SLACK, ContentType.MESSAGE,
                channel + ":" + ts + ":" + workspaceId, channel, null, "#" + channel, text, null, at, at, "U1", "철준"));
        return refs.findById(saved.contentRefId()).orElseThrow();
    }

    private void notionPage(String target, String id, String title) {
        contents.upsert(new ContentRefUpsert(workspaceId, null, Provider.NOTION, ContentType.PAGE, id, target, null,
                title, "본문", "https://notion.so/" + id, LocalDateTime.now()));
    }
}
