package com.togather.content.gmail.client;

import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class GmailApiClient {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final String BASE =
        "https://gmail.googleapis.com/gmail/v1/users/me";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
        new ParameterizedTypeReference<>() {
        };

    private final RestClient externalRestClient;

    /**
     * Gmail 메일 상세 정보.
     *
     * historyId는 이후 증분 동기화 기준으로 사용할 수 있다.
     */
    public record GmailMessage(
        String id,
        String threadId,
        String historyId,
        String subject,
        String from,
        String to,
        String content,
        String snippet,
        LocalDateTime createdAt,
        String deepLink
    ) {
    }

    /**
     * Gmail history.list 결과.
     *
     * changedMessageIds: 추가되거나 변경된 메일
     * deletedMessageIds: 삭제되었거나 휴지통으로 옮긴 메일
     * latestHistoryId: 다음 증분 동기화 기준
     */
    public record HistoryResult(
        List<String> changedMessageIds,
        List<String> deletedMessageIds,
        String latestHistoryId
    ) {
    }

    /**
     * Gmail profile.
     * 최초 동기화가 끝난 뒤 현재 historyId를 저장할 때 사용한다.
     */
    public record GmailProfile(
        String emailAddress,
        String historyId
    ) {
    }

    /** Gmail 계정의 현재 historyId 조회 */
    public GmailProfile getProfile(String token) {
        Map<String, Object> json = call(() ->
            externalRestClient.get()
                .uri(BASE + "/profile")
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(JSON)
        );

        return new GmailProfile(
            str(json, "emailAddress"),
            valueAsString(json.get("historyId"))
        );
    }

    /**
     * 특정 시각 이후의 메일 ID 목록 조회.
     *
     * 최초 동기화 또는 historyId 만료 복구 시 사용한다.
     */
    public List<String> listMessageIds(
        String token,
        LocalDateTime after,
        int maxPages
    ) {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        int pageCount = 0;

        do {
            UriComponentsBuilder builder =
                UriComponentsBuilder.fromUriString(BASE + "/messages")
                    .queryParam("maxResults", 100);

            if (after != null) {
                long epochSeconds = after
                    .atZone(ZONE)
                    .toEpochSecond();

                builder.queryParam(
                    "q",
                    "after:" + epochSeconds
                );
            }

            if (pageToken != null) {
                builder.queryParam(
                    "pageToken",
                    pageToken
                );
            }

            Map<String, Object> json = call(() ->
                externalRestClient.get()
                    .uri(builder.build().encode().toUri())
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .body(JSON)
            );

            list(json, "messages").stream()
                .map(message -> str(message, "id"))
                .filter(id -> id != null && !id.isBlank())
                .forEach(ids::add);

            pageToken = str(json, "nextPageToken");
            pageCount++;

        } while (
            pageToken != null
                && pageCount < maxPages
        );

        return ids;
    }

    /** 메일 한 건의 제목·본문·발신자 등을 조회 */
    public GmailMessage getMessage(
        String token,
        String messageId
    ) {
        String uri = UriComponentsBuilder
            .fromUriString(BASE + "/messages/{messageId}")
            .queryParam("format", "full")
            .encode()
            .buildAndExpand(messageId)
            .toUriString();

        Map<String, Object> json = call(() ->
            externalRestClient.get()
                .uri(uri)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(JSON)
        );

        return toMessage(json);
    }

    /**
     * startHistoryId 이후 변경된 메일을 조회한다.
     *
     * startHistoryId가 Gmail에서 만료된 경우
     * HistoryExpiredException을 발생시킨다.
     */
    public HistoryResult listHistory(
        String token,
        String startHistoryId,
        int maxPages
    ) {
        Set<String> changed = new LinkedHashSet<>();
        Set<String> deleted = new LinkedHashSet<>();

        String pageToken = null;
        String latestHistoryId = startHistoryId;
        int pageCount = 0;

        do {
            UriComponentsBuilder builder =
                UriComponentsBuilder
                    .fromUriString(BASE + "/history")
                    .queryParam(
                        "startHistoryId",
                        startHistoryId
                    )
                    .queryParam("maxResults", 100);

            if (pageToken != null) {
                builder.queryParam(
                    "pageToken",
                    pageToken
                );
            }

            Map<String, Object> json =
                callHistory(() ->
                    externalRestClient.get()
                        .uri(
                            builder
                                .build()
                                .encode()
                                .toUri()
                        )
                        .headers(
                            h -> h.setBearerAuth(token)
                        )
                        .retrieve()
                        .body(JSON)
                );

            for (Map<String, Object> history :
                list(json, "history")) {

                // history.messages에는 해당 history에서
                // 변경된 메시지들이 들어간다.
                for (Map<String, Object> message :
                    list(history, "messages")) {

                    String id = str(message, "id");

                    if (id != null) {
                        changed.add(id);
                    }
                }

                // 삭제된 메일은 따로 기억한다.
                for (Map<String, Object> item :
                    list(history, "messagesDeleted")) {

                    Map<String, Object> message =
                        obj(item, "message");

                    String id =
                        str(message, "id");

                    if (id != null) {
                        deleted.add(id);
                    }
                }

                // 휴지통으로 옮긴 메일은 messagesDeleted가 아니라
                // TRASH 라벨 추가로 온다. 사용자에게는 지운 것과 같으므로 삭제로 본다.
                for (Map<String, Object> item :
                    list(history, "labelsAdded")) {

                    String id =
                        str(obj(item, "message"), "id");

                    if (id != null
                        && hasTrashLabel(item)) {
                        deleted.add(id);
                    }
                }
            }

            String returnedHistoryId =
                valueAsString(
                    json.get("historyId")
                );

            if (returnedHistoryId != null) {
                latestHistoryId =
                    returnedHistoryId;
            }

            pageToken =
                str(json, "nextPageToken");

            pageCount++;

        } while (
            pageToken != null
                && pageCount < maxPages
        );

        // 삭제된 메일은 다시 상세 조회하면 안 된다.
        changed.removeAll(deleted);
        // (휴지통에서 되살린 메일은 이번 범위에서 다시 살리지 않는다.)

        return new HistoryResult(
            List.copyOf(changed),
            List.copyOf(deleted),
            latestHistoryId
        );
    }

    /** labelsAdded 항목에 휴지통(TRASH) 라벨이 붙었는지 */
    private static boolean hasTrashLabel(
        Map<String, Object> labelChange
    ) {
        Object labelIds =
            labelChange.get("labelIds");

        return labelIds instanceof List<?> ids
            && ids.contains("TRASH");
    }

    // ===================== 변환 =====================

    private static GmailMessage toMessage(
        Map<String, Object> json
    ) {
        Map<String, Object> payload =
            obj(json, "payload");

        String subject =
            header(payload, "Subject");

        String from =
            header(payload, "From");

        String to =
            header(payload, "To");

        String content =
            extractBody(payload);

        String internalDate =
            valueAsString(
                json.get("internalDate")
            );

        LocalDateTime createdAt =
            internalDate == null
                ? null
                : LocalDateTime.ofInstant(
                Instant.ofEpochMilli(
                    Long.parseLong(
                        internalDate
                    )
                ),
                ZONE
            );

        String id =
            str(json, "id");

        return new GmailMessage(
            id,
            str(json, "threadId"),
            valueAsString(
                json.get("historyId")
            ),
            subject,
            from,
            to,
            content,
            str(json, "snippet"),
            createdAt,
            gmailLink(id)
        );
    }

    private static String header(
        Map<String, Object> payload,
        String name
    ) {
        return list(payload, "headers")
            .stream()
            .filter(header ->
                name.equalsIgnoreCase(
                    str(header, "name")
                )
            )
            .map(header ->
                str(header, "value")
            )
            .findFirst()
            .orElse(null);
    }

    /**
     * multipart 메일이면 text/plain을 우선 사용하고,
     * 없으면 text/html을 사용한다.
     */
    private static String extractBody(
        Map<String, Object> payload
    ) {
        String mimeType =
            str(payload, "mimeType");

        String direct =
            decodeBody(
                obj(payload, "body")
            );

        if (
            direct != null
                && (
                "text/plain".equalsIgnoreCase(
                    mimeType
                )
                    || "text/html".equalsIgnoreCase(
                    mimeType
                )
            )
        ) {
            return normalizeBody(
                direct,
                mimeType
            );
        }

        List<Map<String, Object>> parts =
            list(payload, "parts");

        // text/plain 우선
        for (Map<String, Object> part :
            parts) {

            if (
                "text/plain".equalsIgnoreCase(
                    str(part, "mimeType")
                )
            ) {
                String text =
                    decodeBody(
                        obj(part, "body")
                    );

                if (text != null) {
                    return text;
                }
            }
        }

        // multipart 안쪽까지 재귀 탐색
        for (Map<String, Object> part :
            parts) {

            String nested =
                extractBody(part);

            if (
                nested != null
                    && !nested.isBlank()
            ) {
                return nested;
            }
        }

        return direct;
    }

    private static String decodeBody(
        Map<String, Object> body
    ) {
        String data =
            str(body, "data");

        if (
            data == null
                || data.isBlank()
        ) {
            return null;
        }

        try {
            byte[] decoded =
                Base64
                    .getUrlDecoder()
                    .decode(data);

            return new String(
                decoded,
                StandardCharsets.UTF_8
            );

        } catch (
            IllegalArgumentException e
        ) {
            return null;
        }
    }

    private static String normalizeBody(
        String body,
        String mimeType
    ) {
        if (
            body == null
                || !"text/html".equalsIgnoreCase(
                mimeType
            )
        ) {
            return body;
        }

        return body
            .replaceAll(
                "(?is)<script.*?>.*?</script>",
                " "
            )
            .replaceAll(
                "(?is)<style.*?>.*?</style>",
                " "
            )
            .replaceAll(
                "(?is)<[^>]+>",
                " "
            )
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replaceAll(
                "[ \\t]+",
                " "
            )
            .replaceAll(
                "\\n\\s*\\n+",
                "\n\n"
            )
            .trim();
    }

    private static String gmailLink(
        String messageId
    ) {
        if (messageId == null) {
            return null;
        }

        return "https://mail.google.com/mail/u/0/#all/"
            + messageId;
    }

    private static String valueAsString(
        Object value
    ) {
        return value == null
            ? null
            : String.valueOf(value);
    }

    // ===================== API 오류 =====================

    private static Map<String, Object> call(
        Supplier<Map<String, Object>> request
    ) {
        try {
            return request.get();

        } catch (
            HttpClientErrorException e
        ) {
            if (
                e.getStatusCode()
                    == HttpStatus.UNAUTHORIZED
            ) {
                throw new BusinessException(
                    ErrorCode.REAUTH_REQUIRED
                );
            }

            log.warn(
                "Gmail API 오류: status={}",
                e.getStatusCode().value()
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );

        } catch (
            RestClientException e
        ) {
            log.warn(
                "Gmail API 호출 실패: {}",
                e.getClass().getSimpleName()
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    /**
     * Gmail historyId가 너무 오래돼 404가 반환된 경우만
     * 일반 외부 API 오류와 구분한다.
     */
    private static Map<String, Object> callHistory(
        Supplier<Map<String, Object>> request
    ) {
        try {
            return request.get();

        } catch (
            HttpClientErrorException e
        ) {
            if (
                e.getStatusCode()
                    == HttpStatus.NOT_FOUND
            ) {
                throw new HistoryExpiredException();
            }

            if (
                e.getStatusCode()
                    == HttpStatus.UNAUTHORIZED
            ) {
                throw new BusinessException(
                    ErrorCode.REAUTH_REQUIRED
                );
            }

            log.warn(
                "Gmail history API 오류: status={}",
                e.getStatusCode().value()
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );

        } catch (
            RestClientException e
        ) {
            log.warn(
                "Gmail history API 호출 실패: {}",
                e.getClass().getSimpleName()
            );

            throw new BusinessException(
                ErrorCode.EXTERNAL_API_ERROR
            );
        }
    }

    public static class HistoryExpiredException
        extends RuntimeException {

        public HistoryExpiredException() {
            super(
                "Gmail historyId has expired"
            );
        }
    }
}
