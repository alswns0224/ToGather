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
import java.util.Set;
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
    /** 하위 페이지가 들어 있을 수 있는 블록 (페이지를 훑을 때 안까지 본다) */
    private static final Set<String> CONTAINER_BLOCKS = Set.of("toggle", "column_list", "column", "synced_block",
            "callout", "heading_1", "heading_2", "heading_3");
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

    /**
     * 페이지와 그 아래 하위 페이지, 페이지 안 데이터베이스의 행을 모은다. (페이지를 연동 대상으로 고른 경우)
     * 토글·열처럼 블록 안에 들어 있는 하위 페이지도 찾는다. 너무 깊거나 많으면 maxDepth·maxPages에서 멈춘다.
     *
     * @return 찾은 페이지와, 한도 때문에 중간에 멈췄는지
     */
    public PageTree crawlPage(String token, String rootPageId, int maxDepth, int maxPages) {
        List<NotionPage> pages = new ArrayList<>();
        NotionPage root = getPage(token, rootPageId);
        pages.add(root);
        boolean truncated = !root.archived() && collectChildren(token, root.id(), 1, maxDepth, maxPages, pages);
        return new PageTree(pages, truncated);
    }

    /** @param truncated 한도에 걸려 일부만 가져왔는지 (그러면 사라진 페이지를 삭제로 표시하지 않는다) */
    public record PageTree(List<NotionPage> pages, boolean truncated) {
    }

    /** 블록 아래를 훑어 하위 페이지를 pages에 넣는다. 한도에 걸리면 true */
    private boolean collectChildren(String token, String blockId, int depth, int maxDepth, int maxPages,
                                    List<NotionPage> pages) {
        if (depth > maxDepth) {
            return true;
        }
        boolean truncated = false;
        for (Map<String, Object> block : getChildren(token, blockId)) {
            if (pages.size() >= maxPages) {
                return true;
            }
            String type = str(block, "type");
            String id = str(block, "id");
            if (bool(block, "archived") || bool(block, "in_trash")) {
                continue;
            }
            if ("child_page".equals(type)) {
                pages.add(new NotionPage(id, null, str(obj(block, "child_page"), "title"), pageUrl(id),
                        toLocal(str(block, "created_time")), toLocal(str(block, "last_edited_time")), false));
                truncated |= collectChildren(token, id, depth + 1, maxDepth, maxPages, pages);
            } else if ("child_database".equals(type)) {
                try {
                    List<NotionPage> rows = queryDatabase(token, id, 1);
                    rows.stream().limit(Math.max(0, maxPages - pages.size())).forEach(pages::add);
                    truncated |= rows.size() >= 100 || pages.size() >= maxPages;
                } catch (BusinessException e) {
                    // 링크된 데이터베이스처럼 통합이 볼 수 없는 데이터베이스는 건너뛴다.
                    log.info("Notion 하위 데이터베이스 조회 건너뜀: {}", e.getErrorCode());
                }
            } else if (bool(block, "has_children") && CONTAINER_BLOCKS.contains(type)) {
                // 토글·열·동기화 블록 안의 하위 페이지 (같은 깊이로 본다)
                truncated |= collectChildren(token, id, depth, maxDepth, maxPages, pages);
            }
        }
        return truncated;
    }

    private static String pageUrl(String pageId) {
        return "https://www.notion.so/" + pageId.replace("-", "");
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

    /**
     * 표는 행까지 읽어 본문 텍스트에 넣는다. (Notion에서 표만 고쳐도 동기화가 바뀐 것으로 알아채고,
     * 통합 검색·논의 정리에서도 표 내용을 쓸 수 있게)
     */
    public PageContent getPageContent(String token, String pageId) {
        List<Map<String, Object>> blocks = getChildren(token, pageId);
        Map<String, List<Map<String, Object>>> tableRows = new HashMap<>();
        for (Map<String, Object> block : blocks) {
            if ("table".equals(str(block, "type")) && bool(block, "has_children")) {
                tableRows.put(str(block, "id"), getChildren(token, str(block, "id")));
            }
        }
        return new PageContent(NotionBlocks.toText(blocks, tableRows), NotionBlocks.isEditable(blocks));
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

    /** 일반 페이지 아래에 하위 페이지로 만든다. (데이터베이스 없이 페이지만 연동한 경우) */
    public NotionPage createChildPage(String token, String parentPageId, String title, String text) {
        List<Map<String, Object>> blocks = NotionBlocks.fromText(text);
        Map<String, Object> body = new HashMap<>();
        body.put("parent", Map.of("page_id", parentPageId));
        body.put("properties", Map.of("title", Map.of("title", NotionBlocks.richText(title))));
        body.put("children", blocks.subList(0, Math.min(blocks.size(), MAX_CHILDREN)));
        Map<String, Object> json = call("pages.create", () -> request(token)
                .post().uri(BASE + "/pages").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON));
        NotionPage page = toPage(json, null);
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

    // ===================== 블록 단위 편집 (이미지·표가 있는 문서) =====================

    /** 블록의 바로 아래 블록 (페이지 본문, 표의 행 등) */
    public List<Map<String, Object>> getBlocks(String token, String blockId) {
        return getChildren(token, blockId);
    }

    /**
     * afterBlockId 바로 뒤에 블록을 넣는다. 100개씩 나눠 넣으며, 다음 묶음은 앞 묶음의 마지막 블록 뒤에 넣는다.
     *
     * @return 새로 만든 블록 ID (넣은 순서)
     */
    public List<String> insertAfter(String token, String parentId, String afterBlockId, List<Map<String, Object>> blocks) {
        List<String> created = new ArrayList<>();
        String after = afterBlockId;
        for (int i = 0; i < blocks.size(); i += MAX_CHILDREN) {
            List<Map<String, Object>> chunk = blocks.subList(i, Math.min(blocks.size(), i + MAX_CHILDREN));
            Map<String, Object> body = new HashMap<>();
            body.put("children", chunk);
            body.put("after", after);
            Map<String, Object> json = call("blocks.append", () -> request(token).patch()
                    .uri(BASE + "/blocks/{id}/children", parentId)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON));
            // 결과의 앞쪽이 방금 넣은 블록이다.
            List<String> ids = list(json, "results").stream().limit(chunk.size()).map(b -> str(b, "id")).toList();
            created.addAll(ids);
            if (!ids.isEmpty()) {
                after = ids.getLast();
            }
        }
        return created;
    }

    /** 맨 뒤에 블록을 넣는다. @return 새로 만든 블록 ID */
    public List<String> appendBlocks(String token, String parentId, List<Map<String, Object>> blocks) {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i += MAX_CHILDREN) {
            List<Map<String, Object>> chunk = blocks.subList(i, Math.min(blocks.size(), i + MAX_CHILDREN));
            Map<String, Object> json = call("blocks.append", () -> request(token).patch()
                    .uri(BASE + "/blocks/{id}/children", parentId)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("children", chunk)).retrieve().body(JSON));
            List<Map<String, Object>> results = list(json, "results");
            // 맨 뒤에 넣었으므로 결과의 뒤쪽이 방금 넣은 블록이다.
            results.subList(Math.max(0, results.size() - chunk.size()), results.size())
                    .forEach(b -> created.add(str(b, "id")));
        }
        return created;
    }

    /** 블록을 지운다. 이미 없으면 성공으로 본다. */
    public void deleteBlock(String token, String blockId) {
        try {
            call("blocks.delete", () -> request(token).delete().uri(BASE + "/blocks/{id}", blockId)
                    .retrieve().body(JSON));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw e;
            }
        }
    }

    /** 표 한 행의 칸을 바꾼다. (칸 전체를 보내야 한다) */
    public void updateTableRow(String token, String rowId, List<String> cells, int width) {
        Map<String, Object> body = Map.of("table_row", Map.of("cells", NotionBlocks.cells(cells, width)));
        call("blocks.update", () -> request(token).patch().uri(BASE + "/blocks/{id}", rowId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON));
    }

    /** 표 맨 아래에 행을 붙인다. */
    public void appendTableRows(String token, String tableId, List<List<String>> rows, int width) {
        List<Map<String, Object>> blocks = rows.stream()
                .map(cells -> Map.<String, Object>of("object", "block", "type", "table_row",
                        "table_row", Map.of("cells", NotionBlocks.cells(cells, width))))
                .toList();
        appendBlocks(token, tableId, blocks);
    }

    /**
     * 이미지 블록 내용. fileUploadId(우리 서버로 올린 파일)나 url(외부 이미지 주소) 중 하나, 캡션.
     * 둘 다 없으면 캡션만 바꾼다.
     */
    public static Map<String, Object> imageBody(String url, String fileUploadId, String caption) {
        Map<String, Object> image = new HashMap<>();
        if (fileUploadId != null && !fileUploadId.isBlank()) {
            image.put("type", "file_upload");
            image.put("file_upload", Map.of("id", fileUploadId));
        } else if (url != null && !url.isBlank()) {
            image.put("type", "external");
            image.put("external", Map.of("url", url));
        }
        image.put("caption", caption == null || caption.isBlank() ? List.of() : NotionBlocks.richText(caption));
        return image;
    }

    /** 이미지 블록을 바꾼다. (파일 업로드를 쓰므로 업로드와 같은 새 API 버전으로 부른다) */
    public void updateImage(String token, String blockId, Map<String, Object> image) {
        call("blocks.update", () -> request(token, FILE_UPLOAD_VERSION).patch().uri(BASE + "/blocks/{id}", blockId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("image", image)).retrieve().body(JSON));
    }

    /**
     * 이미지 블록을 afterBlockId 뒤에 넣는다. afterBlockId가 null이면 맨 뒤에 넣는다.
     *
     * @return 새 블록 ID
     */
    public String insertImage(String token, String parentId, String afterBlockId, Map<String, Object> image) {
        Map<String, Object> body = new HashMap<>();
        body.put("children", List.of(Map.of("object", "block", "type", "image", "image", image)));
        if (afterBlockId != null) {
            body.put("position", Map.of("type", "after_block", "after_block", Map.of("id", afterBlockId)));
        }
        Map<String, Object> json = call("blocks.append", () -> request(token, FILE_UPLOAD_VERSION).patch()
                .uri(BASE + "/blocks/{id}/children", parentId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON));
        List<Map<String, Object>> results = list(json, "results");
        if (results.isEmpty()) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
        // 뒤에 넣었으면 첫 번째, 맨 뒤에 넣었으면 마지막이 새 블록이다.
        return str(afterBlockId != null ? results.getFirst() : results.getLast(), "id");
    }

    /**
     * 파일을 Notion에 올린다. (한 번에 20MB까지. 무료 워크스페이스는 5MB까지)
     * 올린 파일은 1시간 안에 이미지 블록에 붙여야 한다.
     *
     * @return file_upload ID
     */
    public String uploadFile(String token, String filename, String contentType, byte[] bytes) {
        Map<String, Object> created = call("file_uploads.create", () -> request(token, FILE_UPLOAD_VERSION).post()
                .uri(BASE + "/file_uploads").contentType(MediaType.APPLICATION_JSON).body(Map.of())
                .retrieve().body(JSON));
        String id = str(created, "id");
        org.springframework.util.LinkedMultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        partHeaders.setContentDispositionFormData("file", filename);
        form.add("file", new org.springframework.http.HttpEntity<>(bytes, partHeaders));
        call("file_uploads.send", () -> request(token, FILE_UPLOAD_VERSION).post()
                .uri(BASE + "/file_uploads/{id}/send", id).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form).retrieve().body(JSON));
        return id;
    }

    // ===================== 내부 =====================

    /** 파일 업로드 API는 최근 버전에만 있어서 업로드·이미지 블록 호출만 이 버전으로 부른다. (Notion 문서 예시 버전) */
    static final String FILE_UPLOAD_VERSION = "2026-03-11";

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
        return request(token, NotionOAuthClient.NOTION_VERSION);
    }

    private RestClient request(String token, String version) {
        return externalRestClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader("Notion-Version", version)
                .build();
    }

    /** 요청이 몰려 Notion이 잠시 거절(429)하면 이만큼까지 기다렸다 다시 보낸다. (블록 단위 저장은 호출이 많다) */
    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    private static Map<String, Object> call(String action, Supplier<Map<String, Object>> request) {
        for (int attempt = 0; ; attempt++) {
            try {
                return callOnce(action, request);
            } catch (RateLimited e) {
                if (attempt >= MAX_RATE_LIMIT_RETRIES) {
                    log.warn("Notion {} 요청 한도 초과", action);
                    throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
                }
                try {
                    Thread.sleep(e.waitMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
                }
            }
        }
    }

    private static final class RateLimited extends RuntimeException {
        final long waitMillis;

        RateLimited(long waitMillis) {
            super(null, null, false, false);
            this.waitMillis = waitMillis;
        }
    }

    private static Map<String, Object> callOnce(String action, Supplier<Map<String, Object>> request) {
        try {
            return request.get();
        } catch (HttpStatusCodeException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
                long seconds = 1;
                try {
                    seconds = retryAfter == null ? 1 : Math.min(5, Math.max(1, Long.parseLong(retryAfter.trim())));
                } catch (NumberFormatException ignored) {
                    // 기본 1초
                }
                throw new RateLimited(seconds * 1000);
            }
            if (status == HttpStatus.BAD_REQUEST) {
                // 요청 형식 문제는 원인을 알 수 있게 Notion 메시지를 남긴다. (토큰 등 비밀값은 들어 있지 않다)
                log.warn("Notion {} 잘못된 요청: {}", action, e.getResponseBodyAsString());
                throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
            }
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
