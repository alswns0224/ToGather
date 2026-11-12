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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentIndexService {

    /** 임베딩에 넣는 최대 글자 수 (BGE-M3는 긴 글도 받지만 검색에는 앞부분이면 충분하다) */
    static final int MAX_TEXT = 4000;

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
        List<String> texts = new ArrayList<>();
        for (ContentRef ref : contentRefRepository.findAllById(ids)) {
            if (ref.isOriginDeleted() || ref.isDriveTrashed()) {
                removed.add(ref);
                continue;
            }
            String text = textOf(ref);
            if (text.isBlank()) {
                removed.add(ref); // 검색할 내용이 없으면 벡터를 두지 않는다
            } else {
                changed.add(ref);
                texts.add(text);
            }
        }

        vectorStore.delete(removed.stream().map(ContentRef::getContentRefId).toList());
        embeddingRepository.deleteAllById(removed.stream().map(ContentRef::getContentRefId)
                .filter(embeddingRepository::existsById).toList());

        List<float[]> vectors = embeddingClient.embedAll(texts);
        List<VectorDoc> docs = new ArrayList<>();
        List<ContentEmbedding> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < changed.size(); i++) {
            ContentRef ref = changed.get(i);
            docs.add(new VectorDoc(ref.getContentRefId(), ref.getWorkspaceId(), ref.getProvider().name(), vectors.get(i)));
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

    /** 임베딩할 글: 제목(채널명 · 문서 제목 · 파일 이름) + 본문 */
    static String textOf(ContentRef ref) {
        String body = ref.getProvider() == Provider.SLACK
                ? SlackMessageFormat.parse(ref.getContent()).text()
                : ref.getContent();
        StringBuilder sb = new StringBuilder();
        if (ref.getTitle() != null && !ref.getTitle().isBlank()) {
            sb.append(ref.getTitle().strip()).append('\n');
        }
        if (body != null) {
            sb.append(body.strip());
        }
        String text = sb.toString().strip();
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    private static LocalDateTime sourceModifiedAt(ContentRef ref) {
        return ref.getLastModifiedAt() != null ? ref.getLastModifiedAt() : ref.getCreatedAt();
    }
}
