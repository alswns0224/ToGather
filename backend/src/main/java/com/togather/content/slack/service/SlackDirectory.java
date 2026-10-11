package com.togather.content.slack.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.exception.BusinessException;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.workspace.service.WorkspaceMemberService;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Slack 사용자·채널 이름과 ToGather 사용자 매핑. Slack API 호출을 줄이려고 메모리에 1시간 보관한다.
 * ToGather 사용자 ↔ Slack 사용자는 이메일로 연결한다. (Google 로그인 이메일 = Slack 계정 이메일인 경우만)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackDirectory {

    private static final Duration TTL = Duration.ofHours(1);
    private static final Pattern MENTION = Pattern.compile("<@([A-Z0-9]+)(\\|[^>]*)?>");
    /** 이메일로 찾지 못한 경우를 표시 (반복 조회 방지) */
    private static final String NONE = "";

    private final SlackApiClient slackApiClient;
    private final UserService userService;
    private final WorkspaceMemberService workspaceMemberService;

    private final Map<String, Cached<SlackUser>> users = new ConcurrentHashMap<>();
    private final Map<String, Cached<String>> channelNames = new ConcurrentHashMap<>();
    private final Map<String, Cached<String>> teamUrls = new ConcurrentHashMap<>();
    private final Map<String, Cached<String>> slackIdsByUser = new ConcurrentHashMap<>();

    /** Slack 사용자 정보. 조회에 실패한 사용자는 결과에서 빠진다. */
    public Map<String, SlackUser> getUsers(Long workspaceId, String token, Collection<String> slackUserIds) {
        Map<String, SlackUser> result = new HashMap<>();
        for (String id : slackUserIds.stream().filter(Objects::nonNull).distinct().toList()) {
            try {
                SlackUser user = cached(users, workspaceId + ":" + id, () -> slackApiClient.getUser(token, id));
                result.put(id, user);
            } catch (BusinessException e) {
                log.info("Slack 사용자 조회 실패: workspaceId={}, error={}", workspaceId, e.getErrorCode());
            }
        }
        return result;
    }

    public String getChannelName(Long workspaceId, String token, String channelId) {
        try {
            return cached(channelNames, workspaceId + ":" + channelId,
                    () -> slackApiClient.getChannelName(token, channelId));
        } catch (BusinessException e) {
            return channelId;
        }
    }

    /** 이미 조회해 둔 채널 이름. 없으면 채널 ID */
    public String getCachedChannelName(Long workspaceId, String channelId) {
        Cached<String> name = channelNames.get(workspaceId + ":" + channelId);
        return name != null && name.value() != null ? name.value() : channelId;
    }

    /**
     * 메시지 원본 링크. chat.getPermalink를 메시지마다 부르지 않도록 팀 주소로 만든다.
     * 예: https://team.slack.com/archives/C123/p1712345678123456?thread_ts=1712345678.000100&cid=C123
     */
    public String permalink(Long workspaceId, String token, String channelId, String ts, String threadTs) {
        String teamUrl;
        try {
            teamUrl = cached(teamUrls, String.valueOf(workspaceId), () -> slackApiClient.authTest(token).url());
        } catch (BusinessException e) {
            return null;
        }
        if (teamUrl == null) {
            return null;
        }
        String base = (teamUrl.endsWith("/") ? teamUrl : teamUrl + "/") + "archives/" + channelId + "/p"
                + ts.replace(".", "");
        return threadTs != null && !threadTs.equals(ts) ? base + "?thread_ts=" + threadTs + "&cid=" + channelId : base;
    }

    /** ToGather 사용자의 Slack 사용자 ID. 같은 이메일의 Slack 계정이 없으면 빈 값 */
    public Optional<String> findSlackUserId(Long workspaceId, String token, Long userId) {
        try {
            String id = cached(slackIdsByUser, workspaceId + ":" + userId, () -> {
                SlackUser user = slackApiClient.findUserByEmail(token, userService.getUser(userId).email());
                return user == null ? NONE : user.id();
            });
            return NONE.equals(id) ? Optional.empty() : Optional.of(id);
        } catch (BusinessException e) {
            log.info("Slack 사용자 매핑 실패: workspaceId={}, userId={}, error={}", workspaceId, userId, e.getErrorCode());
            return Optional.empty();
        }
    }

    /** Slack 사용자 → 같은 이메일의 워크스페이스 팀원. 없으면 빈 값 (후속 작업 담당자 추천에 사용) */
    public Map<String, Long> matchMembers(Long workspaceId, Collection<SlackUser> slackUsers) {
        List<UserSummary> members = userService.getUsers(workspaceMemberService.getMemberUserIds(workspaceId));
        Map<String, Long> byEmail = new HashMap<>();
        members.forEach(m -> byEmail.put(m.email().toLowerCase(), m.userId()));
        Map<String, Long> result = new HashMap<>();
        for (SlackUser user : slackUsers) {
            if (user.email() != null && byEmail.containsKey(user.email().toLowerCase())) {
                result.put(user.id(), byEmail.get(user.email().toLowerCase()));
            }
        }
        return result;
    }

    /** 본문의 &lt;@U123&gt; 멘션을 @이름으로 바꾼다. 이름을 모르는 사용자는 그대로 둔다. */
    public String renderMentions(Long workspaceId, String text) {
        if (text == null || !text.contains("<@")) {
            return text;
        }
        Matcher matcher = MENTION.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Cached<SlackUser> user = users.get(workspaceId + ":" + matcher.group(1));
            String replacement = user != null && user.value() != null && user.value().name() != null
                    ? "@" + user.value().name() : matcher.group();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static <T> T cached(Map<String, Cached<T>> cache, String key, Supplier<T> loader) {
        Cached<T> entry = cache.get(key);
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.value();
        }
        T value = loader.get();
        cache.put(key, new Cached<>(value, Instant.now().plus(TTL)));
        return value;
    }

    private record Cached<T>(T value, Instant expiresAt) {
    }
}
