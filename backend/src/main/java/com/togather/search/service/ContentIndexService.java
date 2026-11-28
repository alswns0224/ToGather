package com.togather.search.service;

import com.togather.common.enums.Provider;
import com.togather.content.domain.ContentRef;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.slack.service.SlackMessageFormat;
import com.togather.search.client.EmbeddingClient;
import com.togather.search.client.VectorStore;
import com.togather.search.client.VectorStore.VectorDoc;
import com.togather.search.config.SearchProperties;
import com.togather.search.domain.ContentEmbedding;
import com.togather.search.repository.ContentEmbeddingRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 콘텐츠 임베딩 (C-07). 동기화로 새로 저장되거나 바뀐 Slack·Notion·Drive 콘텐츠를 모아서 BGE-M3로 임베딩해 Chroma에 넣고,
 * 원본에서 지워진 콘텐츠는 Chroma에서 뺀다. 화면 갱신(실시간)과 달리 몇 분 늦어도 되므로 스케줄러가 모아서 처리한다.
 * 긴 본문은 {@link ContentChunker}로 조각을 나눠 조각마다 벡터를 만든다. (조각마다 제목을 앞에 붙여 문맥을 준다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentIndexService {

    /** Ollama에 한 번에 보내는 조각 수 */
    static final int EMBED_BATCH = 64;

    private final ContentEmbeddingRepository embeddingRepository;
    private final ContentRefRepository contentRefRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final SearchProperties properties;

    /**
     * 밀린 콘텐츠를 한 묶음(batchSize) 처리한다.
     *
     * @return 처리한 콘텐츠 수. batchSize보다 작으면 밀린 것이 없다.
     * @throws com.togather.common.exception.BusinessException SEARCH_UNAVAILABLE (Ollama·Chroma에 연결할 수 없음)
     */
    public int indexPending() {
        List<Long> ids = embeddingRepository.findStaleContentIds(properties.batchSize());
        if (ids.isEmpty()) {
            return 0;
        }
        List<ContentRef> removed = new ArrayList<>();
        List<ContentRef> changed = new ArrayList<>();
        List<List<String>> chunkTexts = new ArrayList<>();
        for (ContentRef ref : contentRefRepository.findAllById(ids)) {
            if (ref.isOriginDeleted() || ref.isDriveTrashed()) {
                removed.add(ref);
                continue;
            }
            List<String> texts = embedTexts(ref);
            if (texts.isEmpty()) {
                removed.add(ref); // 검색할 내용이 없으면 벡터를 두지 않는다
            } else {
                changed.add(ref);
                chunkTexts.add(texts);
            }
        }

        // 바뀐 콘텐츠도 조각 수가 줄 수 있으므로 예전 조각을 먼저 모두 지운다.
        List<Long> stale = new ArrayList<>(removed.stream().map(ContentRef::getContentRefId).toList());
        stale.addAll(changed.stream().map(ContentRef::getContentRefId).toList());
        vectorStore.delete(stale);
        embeddingRepository.deleteAllById(removed.stream().map(ContentRef::getContentRefId)
                .filter(embeddingRepository::existsById).toList());

        List<String> allTexts = chunkTexts.stream().flatMap(List::stream).toList();
        List<float[]> vectors = new ArrayList<>();
        for (int from = 0; from < allTexts.size(); from += EMBED_BATCH) {
            vectors.addAll(embeddingClient.embedAll(allTexts.subList(from, Math.min(allTexts.size(), from + EMBED_BATCH))));
        }
        List<VectorDoc> docs = new ArrayList<>();
        List<ContentEmbedding> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        int next = 0;
        for (int i = 0; i < changed.size(); i++) {
            ContentRef ref = changed.get(i);
            for (int chunk = 0; chunk < chunkTexts.get(i).size(); chunk++) {
                docs.add(new VectorDoc(ref.getContentRefId(), chunk, ref.getWorkspaceId(), ref.getProvider().name(),
                        vectors.get(next++)));
            }
            records.add(new ContentEmbedding(ref.getContentRefId(), ref.getWorkspaceId(), sourceModifiedAt(ref), now));
        }
        vectorStore.upsert(docs);
        embeddingRepository.saveAll(records);

        // 빈 글이라 벡터를 지운 콘텐츠도 다시 고르지 않도록 기록은 남긴다. (원본이 바뀌면 다시 고른다)
        List<ContentEmbedding> blanks = removed.stream()
                .filter(ref -> !ref.isOriginDeleted() && !ref.isDriveTrashed())
                .map(ref -> new ContentEmbedding(ref.getContentRefId(), ref.getWorkspaceId(), sourceModifiedAt(ref), now))
                .toList();
        embeddingRepository.saveAll(blanks);
        return ids.size();
    }

    public long countPending(Long workspaceId) {
        return embeddingRepository.countPending(workspaceId);
    }

    /** 검색 대상 본문 (Slack은 첨부 표시를 뺀 글) */
    static String bodyOf(ContentRef ref) {
        String body = ref.getProvider() == Provider.SLACK
                ? SlackMessageFormat.parse(ref.getContent()).text()
                : ref.getContent();
        return body == null ? "" : body.strip();
    }

    /** 본문 조각. 검색 결과 미리보기도 같은 조각을 쓴다. */
    static List<String> chunksOf(ContentRef ref) {
        return ContentChunker.split(bodyOf(ref));
    }

    /**
     * 조각마다 임베딩할 글: 제목(채널명 · 문서 제목 · 파일 이름) + 그 조각.
     * 본문이 없으면 제목만 한 조각으로 넣는다. 둘 다 없으면 빈 목록.
     */
    static List<String> embedTexts(ContentRef ref) {
        String title = ref.getTitle() == null ? "" : ref.getTitle().strip();
        List<String> chunks = chunksOf(ref);
        if (chunks.isEmpty()) {
            return title.isEmpty() ? List.of() : List.of(title);
        }
        return chunks.stream().map(chunk -> title.isEmpty() ? chunk : title + "\n" + chunk).toList();
    }

    private static LocalDateTime sourceModifiedAt(ContentRef ref) {
        return ref.getLastModifiedAt() != null ? ref.getLastModifiedAt() : ref.getCreatedAt();
    }
}
