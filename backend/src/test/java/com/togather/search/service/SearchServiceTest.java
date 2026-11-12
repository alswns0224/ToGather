package com.togather.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.search.client.EmbeddingClient;
import com.togather.search.client.VectorStore;
import com.togather.search.dto.SearchResponse;
import com.togather.search.dto.SearchResponse.Item;
import com.togather.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * 색인(C-07) → 통합 검색(C-08) 흐름. BGE-M3·Chroma 대신 단어 기반 가짜 임베딩과 메모리 벡터 저장소를 쓰고,
 * content_ref·content_embedding은 내장 PostgreSQL에 실제로 저장한다.
 */
@TestPropertySource(properties = {"togather.search.min-score=0.1", "togather.search.batch-size=10"})
class SearchServiceTest extends IntegrationTestSupport {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 10, 10, 0);

    @TestConfiguration
    static class FakeSearchConfig {

        @Bean
        @Primary
        EmbeddingClient fakeEmbeddingClient() {
            return texts -> texts.stream().map(FakeSearchConfig::embed).toList();
        }

        @Bean
        @Primary
        InMemoryVectorStore inMemoryVectorStore() {
            return new InMemoryVectorStore();
        }

        /** 정해진 단어가 들어 있는지로 만든 벡터 (마지막 칸은 0벡터를 피하기 위한 값) */
        static float[] embed(String text) {
            String[] words = {"발표", "슬라이드", "회의", "리허설", "예산", "디자인"};
            float[] v = new float[words.length + 1];
            for (int i = 0; i < words.length; i++) {
                v[i] = text.contains(words[i]) ? 1f : 0f;
            }
            v[words.length] = 0.1f;
            return v;
        }
    }

    static class InMemoryVectorStore implements VectorStore {

        final Map<Long, VectorDoc> docs = new ConcurrentHashMap<>();

        @Override
        public void upsert(List<VectorDoc> list) {
            list.forEach(doc -> docs.put(doc.contentRefId(), doc));
        }

        @Override
        public void delete(Collection<Long> ids) {
            ids.forEach(docs::remove);
        }

        @Override
        public List<VectorHit> searchVectors(float[] vector, Long workspaceId, Collection<String> providers, int limit) {
            return docs.values().stream()
                    .filter(d -> d.workspaceId().equals(workspaceId) && providers.contains(d.provider()))
                    .map(d -> new VectorHit(d.contentRefId(), 1 - cosine(vector, d.vector())))
                    .sorted(Comparator.comparingDouble(VectorHit::distance))
                    .limit(limit)
                    .toList();
        }

        private static double cosine(float[] a, float[] b) {
            double dot = 0, na = 0, nb = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
            }
            return dot / (Math.sqrt(na) * Math.sqrt(nb));
        }
    }

    @Autowired
    private ContentIndexService contentIndexService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private ContentRefService contentRefService;
    @Autowired
    private InMemoryVectorStore vectorStore;

    private Long userId;
    private Long workspaceId;
    private ContentRefDTO slackRoot;
    private ContentRefDTO slackReply;
    private ContentRefDTO notionPage;
    private ContentRefDTO driveFile;

    @BeforeEach
    void setUp() {
        vectorStore.docs.clear();
        userId = createUser("민준", "mj@test.com");
        workspaceId = createWorkspace(userId);
        slackRoot = save(Provider.SLACK, ContentType.MESSAGE, "C1:100.1", "C1", null, "#general",
                "다음 주 팀 프로젝트 발표 준비 어떻게 나눌까요?", T0);
        slackReply = save(Provider.SLACK, ContentType.MESSAGE, "C1:100.2", "C1", slackRoot.contentRefId(), "#general",
                "발표 슬라이드는 민준님이 금요일까지 만들어 주세요", T0.plusMinutes(1));
        notionPage = save(Provider.NOTION, ContentType.PAGE, "page-1", "db-1", null, "리허설 회의록",
                "월요일 4시 리허설 회의에서 정한 내용", T0);
        driveFile = save(Provider.GOOGLE_DRIVE, ContentType.FILE, "file-1", "folder-1", null, "예산표.xlsx",
                "파일 유형: 스프레드시트", T0);
    }

    @Test
    void 색인한_콘텐츠를_관련도순으로_찾는다() {
        assertThat(contentIndexService.indexPending()).isEqualTo(4);

        SearchResponse result = searchService.search(workspaceId, userId, "발표 슬라이드", null, 10);

        assertThat(result.items()).extracting(Item::contentRefId).first().isEqualTo(slackReply.contentRefId());
        assertThat(result.items().getFirst().score()).isGreaterThan(result.items().get(1).score());
        assertThat(result.pending()).isZero();
    }

    @Test
    void 출처_서비스로_거른다() {
        contentIndexService.indexPending();

        SearchResponse result = searchService.search(workspaceId, userId, "회의 리허설 발표", List.of(Provider.NOTION), 10);

        assertThat(result.items()).extracting(Item::provider).containsOnly(Provider.NOTION);
        assertThat(result.items()).extracting(Item::contentRefId).containsExactly(notionPage.contentRefId());
    }

    @Test
    void Slack_답글은_스레드로_열고_Notion은_문서로_연다() {
        contentIndexService.indexPending();

        List<Item> items = searchService.search(workspaceId, userId, "발표 리허설", null, 10).items();

        assertThat(find(items, slackReply.contentRefId()).appPath()).isEqualTo("/slack?channel=C1&thread=100.1");
        assertThat(find(items, notionPage.contentRefId()).appPath()).isEqualTo("/notion?page=" + notionPage.contentRefId());
    }

    @Test
    void 바뀐_콘텐츠만_다시_임베딩한다() {
        contentIndexService.indexPending();
        assertThat(contentIndexService.indexPending()).isZero();

        save(Provider.NOTION, ContentType.PAGE, "page-1", "db-1", null, "디자인 회의록", "디자인 시안 검토", T0.plusHours(1));

        assertThat(contentIndexService.countPending(workspaceId)).isEqualTo(1);
        assertThat(contentIndexService.indexPending()).isEqualTo(1);
        List<Item> items = searchService.search(workspaceId, userId, "디자인", null, 10).items();
        assertThat(items).extracting(Item::contentRefId).first().isEqualTo(notionPage.contentRefId());
    }

    @Test
    void 원본에서_지운_콘텐츠는_벡터를_지우고_검색에서_뺀다() {
        contentIndexService.indexPending();

        contentRefService.markOriginDeleted(driveFile.contentRefId());
        contentIndexService.indexPending();

        assertThat(vectorStore.docs).doesNotContainKey(driveFile.contentRefId());
        assertThat(searchService.search(workspaceId, userId, "예산", null, 10).items())
                .extracting(Item::contentRefId).doesNotContain(driveFile.contentRefId());
    }

    @Test
    void 결과에_연결된_Task를_함께_보여준다() {
        contentIndexService.indexPending();
        Long taskId = jdbc.queryForObject("""
                INSERT INTO task (workspace_id, title, status, created_at, updated_at)
                VALUES (?, '발표 슬라이드 작성', 'IN_PROGRESS', now(), now()) RETURNING task_id
                """, Long.class, workspaceId);
        jdbc.update("""
                INSERT INTO task_content_link (task_id, content_ref_id, link_type, created_at, updated_at)
                VALUES (?, ?, 'REFERENCE', now(), now())
                """, taskId, slackReply.contentRefId());

        Item item = find(searchService.search(workspaceId, userId, "발표 슬라이드", null, 10).items(),
                slackReply.contentRefId());

        assertThat(item.tasks()).extracting(t -> t.title()).containsExactly("발표 슬라이드 작성");
    }

    @Test
    void 다른_워크스페이스_콘텐츠는_나오지_않고_팀원이_아니면_검색할_수_없다() {
        Long otherUser = createUser("외부인", "out@test.com");
        Long otherWorkspace = createWorkspace(otherUser);
        contentIndexService.indexPending();

        assertThat(searchService.search(otherWorkspace, otherUser, "발표", null, 10).items()).isEmpty();
        assertThatThrownBy(() -> searchService.search(workspaceId, otherUser, "발표", null, 10))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 빈_검색어와_검색_대상이_아닌_서비스는_거절한다() {
        assertThatThrownBy(() -> searchService.search(workspaceId, userId, "  ", null, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> searchService.search(workspaceId, userId, "발표", List.of(Provider.GMAIL), 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    private ContentRefDTO save(Provider provider, ContentType type, String originId, String channelId, Long parentRefId,
                               String title, String content, LocalDateTime modifiedAt) {
        return contentRefService.upsert(new ContentRefUpsert(workspaceId, null, provider, type, originId, channelId,
                parentRefId, title, content, "https://example.com/" + originId, T0, modifiedAt, null, "민준"));
    }

    private static Item find(List<Item> items, Long contentRefId) {
        return items.stream().filter(i -> i.contentRefId().equals(contentRefId)).findFirst().orElseThrow();
    }
}
