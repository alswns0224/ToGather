package com.togather.content.notion.client;

import static com.togather.common.util.JsonValues.bool;
import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.integration.client.NotionOAuthClient;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Notion API (B-06). 연동(OAuth)과 같은 API 버전(NotionOAuthClient.NOTION_VERSION)을 쓴다.
 * 시간은 서울 시간으로 바꿔서 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotionApiClient {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String BASE = "https://api.notion.com/v1";
    /** 블록 추가 API 한 번에 넣을 수 있는 최대 개수 */
    private static final int MAX_CHILDREN = 100;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient externalRestClient;

    public record NotionPage(String id, String databaseId, String title, String url, LocalDateTime createdAt,
                             LocalDateTime lastEditedAt, boolean archived) {
    }

    /** @param titleProperty 데이터베이스의 제목 속성 이름 (페이지 생성 시 필요) */
    public record NotionDatabase(String id, String title, String titleProperty) {
    }

    // ===================== 조회 =====================

    public NotionDatabase getDatabase(String token, String databaseId) {
        Map<String, Object> json = call("databases.retrieve", () -> request(token)
                .get().uri(BASE + "/databases/{id}", databaseId).retrieve().body(JSON));
        String titleProperty = obj(json, "properties").entrySet().stream()
                .filter(e -> e.getValue() instanceof Map<?, ?> p && "title".equals(p.get("type")))
                .map(Map.Entry::getKey)
                .findFirst().orElse("Name");
        return new NotionDatabase(str(json, "id"), NotionBlocks.plainText(list(json, "title")), titleProperty);
    }

    /** 데이터베이스의 페이지 전체 (최근 수정순, 최대 maxPages × 100개) */
    public List<NotionPage> queryDatabase(String token, String databaseId, int maxPages) {
        List<NotionPage> pages = new ArrayList<>();
        String cursor = null;
        int count = 0;
        do {
            Map<String, Object> body = new HashMap<>();
            body.put("page_size", 100);
            body.put("sorts", List.of(Map.of("timestamp", "last_edited_time", "direction", "descending")));
            if (cursor != null) {
                body.put("start_cursor", cursor);
            }
            Map<String, Object> json = call("databases.query", () -> request(token)
                    .post().uri(BASE + "/databases/{id}/query", databaseId)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON));
            list(json, "results").stream().map(p -> toPage(p, databaseId)).forEach(pages::add);
            cursor = bool(json, "has_more") ? str(json, "next_cursor") : null;
            count++;
        } while (cursor != null && count < maxPages);
        return pages;
    }

    public NotionPage getPage(String token, String pageId) {
        Map<String, Object> json = call("pages.retrieve", () -> request(token)
                .get().uri(BASE + "/pages/{id}", pageId).retrieve().body(JSON));
        return toPage(json, str(obj(json, "parent"), "database_id"));
    }

    /** 페이지 본문(최상위 블록)을 텍스트로 */
    public String getPageText(String token, String pageId) {
        return getPageContent(token, pageId).text();
    }

    /** @param editable ToGather에서 본문을 수정해도 되는지 (NotionBlocks.isEditable) */
    public record PageContent(String text, boolean editable) {
    }

    public PageContent getPageContent(String token, String pageId) {
        List<Map<String, Object>> blocks = getChildren(token, pageId);
        return new PageContent(NotionBlocks.toText(blocks), NotionBlocks.isEditable(blocks));
    }

    // ===================== 생성·수정·삭제 =====================

    public NotionPage createPage(String token, NotionDatabase database, String title, String text) {
        List<Map<String, Object>> blocks = NotionBlocks.fromText(text);
        Map<String, Object> body = new HashMap<>();
        body.put("parent", Map.of("database_id", database.id()));
        body.put("properties", Map.of(database.titleProperty(), Map.of("title", NotionBlocks.richText(title))));
        body.put("children", blocks.subList(0, Math.min(blocks.size(), MAX_CHILDREN)));
        Map<String, Object> json = call("pages.create", () -> request(token)
                .post().uri(BASE + "/pages").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON));
        NotionPage page = toPage(json, database.id());
        if (blocks.size() > MAX_CHILDREN) {
            appendChildren(token, page.id(), blocks.subList(MAX_CHILDREN, blocks.size()));
        }
        return page;
    }

    /** 제목과 본문을 바꾼다. 본문은 기존 블록을 지우고 새로 넣는다. */
    public NotionPage updatePage(String token, String pageId, String titleProperty, String title, String text) {
        List<Map<String, Object>> current = List.of();
        if (text != null) {
            current = getChildren(token, pageId);
            if (!NotionBlocks.isEditable(current)) {
                // 이미지·표·하위 블록이 지워지지 않도록 본문 교체를 막는다. 제목도 바꾸지 않는다.
                throw new BusinessException(ErrorCode.NOTION_BODY_NOT_EDITABLE);
            }
        }
        if (title != null) {
            Map<String, Object> body = Map.of("properties",
                    Map.of(titleProperty, Map.of("title", NotionBlocks.richText(title))));
            call("pages.update", () -> request(token).patch().uri(BASE + "/pages/{id}", pageId)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON));
        }
        if (text != null) {
            // 새 본문을 먼저 붙이고 기존 블록을 지운다. 중간에 실패해도 내용이 사라지지 않고 중복으로 남는다.
            appendChildren(token, pageId, NotionBlocks.fromText(text));
            for (Map<String, Object> block : current) {
                String blockId = str(block, "id");
                call("blocks.delete", () -> request(token).delete().uri(BASE + "/blocks/{id}", blockId)
                        .retrieve().body(JSON));
            }
        }
        return getPage(token, pageId);
    }

    /** 휴지통으로 옮긴다. 이미 없으면 성공으로 본다. */
    public void archivePage(String token, String pageId) {
        try {
            call("pages.archive", () -> request(token).patch().uri(BASE + "/pages/{id}", pageId)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("archived", true)).retrieve().body(JSON));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw e;
            }
        }
    }

    // ===================== 내부 =====================

    private List<Map<String, Object>> getChildren(String token, String blockId) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        String cursor = null;
        int count = 0;
        do {
            String uri = BASE + "/blocks/" + blockId + "/children?page_size=100"
                    + (cursor != null ? "&start_cursor=" + cursor : "");
            Map<String, Object> json = call("blocks.children", () -> request(token).get().uri(uri)
                    .retrieve().body(JSON));
            blocks.addAll(list(json, "results"));
            cursor = bool(json, "has_more") ? str(json, "next_cursor") : null;
            count++;
        } while (cursor != null && count < 10);
        return blocks;
    }

    private void appendChildren(String token, String pageId, List<Map<String, Object>> blocks) {
        for (int i = 0; i < blocks.size(); i += MAX_CHILDREN) {
            List<Map<String, Object>> chunk = blocks.subList(i, Math.min(blocks.size(), i + MAX_CHILDREN));
            call("blocks.append", () -> request(token).patch().uri(BASE + "/blocks/{id}/children", pageId)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("children", chunk))
                    .retrieve().body(JSON));
        }
    }

    private static NotionPage toPage(Map<String, Object> json, String databaseId) {
        String title = obj(json, "properties").values().stream()
                .filter(v -> v instanceof Map<?, ?> p && "title".equals(p.get("type")))
                .map(v -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> property = (Map<String, Object>) v;
                    return NotionBlocks.plainText(list(property, "title"));
                })
                .findFirst().orElse("");
        boolean archived = bool(json, "archived") || bool(json, "in_trash");
        return new NotionPage(str(json, "id"), databaseId, title, str(json, "url"), toLocal(str(json, "created_time")),
                toLocal(str(json, "last_edited_time")), archived);
    }

    static LocalDateTime toLocal(String iso) {
        return iso == null ? null : OffsetDateTime.parse(iso).atZoneSameInstant(ZONE).toLocalDateTime();
    }

    private RestClient request(String token) {
        return externalRestClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader("Notion-Version", NotionOAuthClient.NOTION_VERSION)
                .build();
    }

    private static Map<String, Object> call(String action, Supplier<Map<String, Object>> request) {
        try {
            return request.get();
        } catch (HttpStatusCodeException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.UNAUTHORIZED) {
                throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
            }
            if (status == HttpStatus.NOT_FOUND) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            if (status == HttpStatus.FORBIDDEN) {
                // 통합에 공유되지 않은 페이지·데이터베이스
                log.info("Notion {} 권한 없음", action);
                throw new BusinessException(ErrorCode.NOTION_ACCESS_DENIED);
            }
            log.warn("Notion {} 오류: status={}", action, e.getStatusCode().value());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        } catch (RestClientException e) {
            log.warn("Notion {} 호출 실패: {}", action, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }
}
