package com.togather.content.slack.client;

import static com.togather.common.util.JsonValues.bool;
import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.longValue;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Slack Web API (B-04, B-05). 시간은 서울 시간(LocalDateTime)으로 바꿔서 넘긴다.
 * Slack은 실패해도 HTTP 200에 {"ok": false, "error": "..."}를 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackApiClient {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String BASE = "https://slack.com/api";
    private static final int PAGE_LIMIT = 200;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    /** 토큰이 만료·취소된 경우 */
    private static final Set<String> AUTH_ERRORS = Set.of(
            "invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive");
    private static final Set<String> NOT_FOUND_ERRORS = Set.of("message_not_found", "thread_not_found");
    private static final Set<String> FORBIDDEN_ERRORS = Set.of(
            "cant_update_message", "cant_delete_message", "edit_window_closed", "not_in_channel",
            "is_archived", "restricted_action");

    private final RestClient externalRestClient;

    /**
     * @param ts       Slack 메시지 ID (예: "1712345678.123456")
     * @param threadTs 스레드의 root ts. 스레드가 아니면 null
     * @param editedAt 수정된 적 없으면 null
     */
    public record SlackMessage(String ts, String threadTs, String userId, String text, int replyCount,
                               String latestReplyTs, LocalDateTime createdAt, LocalDateTime editedAt,
                               List<SlackFile> files, String subtype) {

        public boolean isReply() {
            return threadTs != null && !threadTs.equals(ts);
        }

        public boolean hasReplies() {
            return replyCount > 0;
        }
    }

    public record SlackFile(String name, String url, String mimetype) {
    }

    public record SlackUser(String id, String name, String email) {
    }

    /** @param url 팀 주소 (예: https://myteam.slack.com/) */
    public record AuthInfo(String teamId, String userId, String url) {
    }

    public record HistoryPage(List<SlackMessage> messages, boolean truncated) {
    }

    public record DirectMessage(String id, String userId) {
    }

    public record DirectMessagePage(List<SlackMessage> messages, String nextCursor) {
    }

    /**
     * 읽을 수 없는 대화(channel_not_found). 비활성 사용자·시스템 봇과의 DM처럼 목록에는 나오지만 기록은 못 읽는 대화가 있다.
     * 다른 곳에서는 기존처럼 외부 API 오류로 다루고, 개인 DM 수집만 이 예외를 보고 그 대화를 건너뛴다.
     */
    public static class ConversationNotFoundException extends BusinessException {
        public ConversationNotFoundException() {
            super(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /** 429의 Retry-After를 개인 수집의 재시도 시각에 반영한다. */
    public static class RateLimitException extends BusinessException {
        private final long retryAfterSeconds;

        public RateLimitException(long retryAfterSeconds) {
            super(ErrorCode.SLACK_RATE_LIMITED);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    /**
     * 토큰 주인이 멤버로 들어가 있는 공개·비공개 채널 ID (users.conversations).
     * 팀원별로 ToGather에서 볼 수 있는 채널을 Slack 권한과 똑같이 맞출 때 쓴다.
     */
    public Set<String> getMemberChannelIds(String token) {
        Map<String, String> query = new HashMap<>(Map.of(
                "types", "public_channel,private_channel", "exclude_archived", "true", "limit", "200"));
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (int page = 0; page < 20; page++) {
            Map<String, Object> json = get(token, "users.conversations", query);
            for (Map<String, Object> item : list(json, "channels")) {
                String id = str(item, "id");
                if (id != null) {
                    ids.add(id);
                }
            }
            String cursor = str(obj(json, "response_metadata"), "next_cursor");
            if (cursor == null || cursor.isBlank()) {
                return ids;
            }
            query.put("cursor", cursor);
        }
        return ids;
    }

    /** 본인 사용자 토큰으로 접근 가능한 1:1 DM만 조회한다. 대화를 열거나 메시지를 보내지 않는다. */
    public List<DirectMessage> getDirectMessages(String token) {
        Map<String, String> query = new HashMap<>(Map.of("types", "im", "limit", "200"));
        Map<String, DirectMessage> result = new java.util.LinkedHashMap<>();
        for (int page = 0; page < 20; page++) {
            Map<String, Object> json = get(token, "conversations.list", query);
            for (Map<String, Object> item : list(json, "channels")) {
                String id = str(item, "id");
                if (id != null && id.startsWith("D") && bool(item, "is_im") && !bool(item, "is_mpim")) {
                    result.put(id, new DirectMessage(id, str(item, "user")));
                }
            }
            String cursor = str(obj(json, "response_metadata"), "next_cursor");
            if (cursor == null || cursor.isBlank()) return List.copyOf(result.values());
            query.put("cursor", cursor);
        }
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }

    /** 고정된 시간 구간과 커서로 이어받는다. 잘린 페이지를 완료로 취급하지 않는다. */
    public DirectMessagePage getDirectMessageHistory(String token, String conversationId,
                                                     LocalDateTime oldest, LocalDateTime latest, String cursor) {
        return getDirectMessagePage(token, conversationId, null, oldest, latest, cursor);
    }

    public DirectMessagePage getDirectMessageReplies(String token, String conversationId, String threadTs,
                                                     LocalDateTime oldest, LocalDateTime latest, String cursor) {
        return getDirectMessagePage(token, conversationId, threadTs, oldest, latest, cursor);
    }

    private DirectMessagePage getDirectMessagePage(String token, String conversationId, String threadTs,
                                                  LocalDateTime oldest, LocalDateTime latest, String cursor) {
        if (conversationId == null || !conversationId.matches("D[A-Z0-9]+")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Map<String, String> query = new HashMap<>(Map.of("channel", conversationId, "limit", "100",
                "oldest", toTs(oldest), "latest", toTs(latest), "inclusive", "true"));
        if (cursor != null && !cursor.isBlank()) query.put("cursor", cursor);
        if (threadTs != null) query.put("ts", threadTs);
        Map<String, Object> json = get(token, threadTs == null ? "conversations.history" : "conversations.replies", query);
        String next = str(obj(json, "response_metadata"), "next_cursor");
        if (bool(json, "has_more") && (next == null || next.isBlank())) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
        return new DirectMessagePage(list(json, "messages").stream().map(item -> toMessage(item, null)).toList(),
                next == null || next.isBlank() ? null : next);
    }

    // ===================== 조회 =====================

    /**
     * 채널의 최상위 메시지(스레드 root 포함). oldest 이후만, 최신순. maxPages를 넘으면 truncated=true
     *
     * @param oldest null이면 처음부터
     */
    public HistoryPage getHistory(String token, String channelId, LocalDateTime oldest, int maxPages) {
        return paginate(token, "conversations.history", Map.of("channel", channelId), oldest, maxPages);
    }

    /** 스레드의 root와 답글. 시간순 */
    public List<SlackMessage> getReplies(String token, String channelId, String threadTs, LocalDateTime oldest) {
        return getThreadPage(token, channelId, threadTs, oldest).messages();
    }

    /**
     * 스레드 전체(root와 모든 답글). 시간순. 지워진 답글을 찾으려면 일부만 받았는지(truncated) 알아야 해서 따로 둔다.
     *
     * @throws BusinessException RESOURCE_NOT_FOUND 스레드 root가 삭제된 경우
     */
    public HistoryPage getThread(String token, String channelId, String threadTs) {
        return getThreadPage(token, channelId, threadTs, null);
    }

    private HistoryPage getThreadPage(String token, String channelId, String threadTs, LocalDateTime oldest) {
        Map<String, String> params = Map.of("channel", channelId, "ts", threadTs);
        HistoryPage page = paginate(token, "conversations.replies", params, oldest, 10);
        List<SlackMessage> messages = new ArrayList<>(page.messages());
        messages.sort((a, b) -> new BigDecimal(a.ts()).compareTo(new BigDecimal(b.ts())));
        return new HistoryPage(messages, page.truncated());
    }

    public String getChannelName(String token, String channelId) {
        Map<String, Object> json = get(token, "conversations.info", Map.of("channel", channelId));
        return str(obj(json, "channel"), "name");
    }

    public SlackUser getUser(String token, String userId) {
        Map<String, Object> user = obj(get(token, "users.info", Map.of("user", userId)), "user");
        return toUser(user);
    }

    /** 이메일로 Slack 사용자를 찾는다. 없으면 null (users:read.email 권한 필요) */
    public SlackUser findUserByEmail(String token, String email) {
        try {
            return toUser(obj(get(token, "users.lookupByEmail", Map.of("email", email)), "user"));
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    public AuthInfo authTest(String token) {
        Map<String, Object> json = post(token, "auth.test", Map.of());
        return new AuthInfo(str(json, "team_id"), str(json, "user_id"), str(json, "url"));
    }

    public String getPermalink(String token, String channelId, String ts) {
        return str(get(token, "chat.getPermalink", Map.of("channel", channelId, "message_ts", ts)), "permalink");
    }

    // ===================== 작성·수정·삭제 =====================

    /** @param threadTs 답글이면 root ts, 채널 메시지면 null */
    public SlackMessage postMessage(String token, String channelId, String threadTs, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("channel", channelId);
        body.put("text", text);
        if (threadTs != null) {
            body.put("thread_ts", threadTs);
        }
        Map<String, Object> json = post(token, "chat.postMessage", body);
        return toMessage(obj(json, "message"), str(json, "ts"));
    }

    public SlackMessage updateMessage(String token, String channelId, String ts, String text) {
        Map<String, Object> json = post(token, "chat.update", Map.of("channel", channelId, "ts", ts, "text", text));
        Map<String, Object> message = obj(json, "message");
        SlackMessage updated = toMessage(message, ts);
        return updated.editedAt() != null ? updated : withEditedNow(updated);
    }

    /** 이미 삭제된 메시지면 조용히 성공으로 본다. */
    public void deleteMessage(String token, String channelId, String ts) {
        try {
            post(token, "chat.delete", Map.of("channel", channelId, "ts", ts));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw e;
            }
        }
    }

    // ===================== 변환 =====================

    public static LocalDateTime toLocal(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        BigDecimal seconds = new BigDecimal(ts);
        long epochSecond = seconds.longValue();
        long nanos = seconds.subtract(BigDecimal.valueOf(epochSecond)).movePointRight(9).longValue();
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nanos), ZONE);
    }

    public static String toTs(LocalDateTime time) {
        Instant instant = time.atZone(ZONE).toInstant();
        return instant.getEpochSecond() + "." + String.format("%06d", instant.getNano() / 1000);
    }

    static SlackMessage toMessage(Map<String, Object> json, String fallbackTs) {
        String ts = str(json, "ts") != null ? str(json, "ts") : fallbackTs;
        Long replyCount = longValue(json, "reply_count");
        List<SlackFile> files = list(json, "files").stream()
                .map(f -> new SlackFile(str(f, "name"), str(f, "permalink"), str(f, "mimetype")))
                .toList();
        return new SlackMessage(ts, str(json, "thread_ts"), str(json, "user"), str(json, "text"),
                replyCount == null ? 0 : replyCount.intValue(), str(json, "latest_reply"), toLocal(ts),
                toLocal(str(obj(json, "edited"), "ts")), files, str(json, "subtype"));
    }

    private static SlackMessage withEditedNow(SlackMessage m) {
        return new SlackMessage(m.ts(), m.threadTs(), m.userId(), m.text(), m.replyCount(), m.latestReplyTs(),
                m.createdAt(), LocalDateTime.now(ZONE), m.files(), m.subtype());
    }

    private static SlackUser toUser(Map<String, Object> user) {
        Map<String, Object> profile = obj(user, "profile");
        String displayName = str(profile, "display_name");
        String realName = str(profile, "real_name") != null ? str(profile, "real_name") : str(user, "real_name");
        String name = displayName != null && !displayName.isBlank() ? displayName
                : realName != null && !realName.isBlank() ? realName : str(user, "name");
        return new SlackUser(str(user, "id"), name, str(profile, "email"));
    }

    // ===================== 내부 =====================

    private HistoryPage paginate(String token, String method, Map<String, String> params, LocalDateTime oldest,
                                 int maxPages) {
        List<SlackMessage> messages = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, String> query = new HashMap<>(params);
            query.put("limit", String.valueOf(PAGE_LIMIT));
            if (oldest != null) {
                query.put("oldest", toTs(oldest));
            }
            if (cursor != null) {
                query.put("cursor", cursor);
            }
            Map<String, Object> json = get(token, method, query);
            list(json, "messages").stream().map(m -> toMessage(m, null)).forEach(messages::add);
            cursor = str(obj(json, "response_metadata"), "next_cursor");
            if (cursor != null && cursor.isBlank()) {
                cursor = null;
            }
            pages++;
        } while (cursor != null && pages < maxPages);
        return new HistoryPage(messages, cursor != null);
    }

    private Map<String, Object> get(String token, String method, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE + "/" + method);
        params.forEach((name, value) -> builder.queryParam(name, "{" + name + "}"));
        java.net.URI uri = builder.encode().buildAndExpand(params).toUri();
        return call(method, () -> externalRestClient.get()
                .uri(uri)
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(JSON));
    }

    private Map<String, Object> post(String token, String method, Map<String, Object> body) {
        return call(method, () -> externalRestClient.post()
                .uri(BASE + "/" + method)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JSON));
    }

    private static Map<String, Object> call(String method, Supplier<Map<String, Object>> request) {
        Map<String, Object> json;
        try {
            json = request.get();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                long retryAfter = 60;
                try {
                    retryAfter = Math.max(1, Long.parseLong(e.getResponseHeaders().getFirst("Retry-After")));
                } catch (RuntimeException ignored) { }
                throw new RateLimitException(retryAfter);
            } else {
                log.warn("Slack {} HTTP 오류: status={}", method, e.getStatusCode().value());
            }
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        } catch (RestClientException e) {
            log.warn("Slack {} 호출 실패: {}", method, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
        if (bool(json, "ok")) {
            return json;
        }
        String error = str(json, "error");
        if ("missing_scope".equals(error)) {
            throw new BusinessException(ErrorCode.SLACK_SCOPE_REQUIRED);
        }
        if (AUTH_ERRORS.contains(error)) {
            throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
        }
        if (NOT_FOUND_ERRORS.contains(error) || "users_not_found".equals(error)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (FORBIDDEN_ERRORS.contains(error)) {
            log.info("Slack {} 권한 없음: {}", method, error);
            throw new BusinessException(ErrorCode.SLACK_ACTION_FORBIDDEN);
        }
        if ("channel_not_found".equals(error)) {
            log.info("Slack {}: 대화를 찾을 수 없음 (channel_not_found)", method);
            throw new ConversationNotFoundException();
        }
        log.warn("Slack {} 오류: {}", method, error);
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
}
