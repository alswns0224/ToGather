package com.togather.content.slack.service;

import static com.togather.common.util.JsonValues.list;
import static com.togather.common.util.JsonValues.obj;
import static com.togather.common.util.JsonValues.str;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.DirectMessage;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.content.slack.client.SlackApiClient.SlackUser;
import com.togather.content.slack.dto.SlackDmResponse.Conversation;
import com.togather.content.slack.dto.SlackDmResponse.Message;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.ConnectionOwner;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 내 Slack 1:1 DM 보기·답장 (UI-04).
 * DM은 본인만 보는 개인 자료라 팀 토큰이 아니라 반드시 내 Slack 연결의 토큰으로 읽고 보낸다.
 * DB에 저장하지 않는다. 처음 열 때만 Slack에서 읽어 기억해 두고, 그 뒤로는 Slack이 보내 주는 DM 이벤트(웹훅)로
 * 기억한 목록·메시지를 고친 뒤 본인 화면에만 알린다. Slack의 대화 기록 조회 한도에 걸리지 않게 하려는 것이다.
 * (AI 업무 보조용 수집은 SlackDirectMessageService가 따로 한다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackDmService {

    /** 화면에 보여 줄 기간 (한 번에 최근 100개까지) */
    static final int RECENT_DAYS = 30;
    /**
     * Slack은 대화 기록 조회 횟수를 엄격하게 제한한다. 이 시간 안에는 같은 사람에게 Slack을 다시 부르지 않고 기억한 결과를 준다.
     * 한도에 걸리면 기억한 결과가 오래됐어도 그것을 준다.
     */
    static final Duration LIST_TTL = Duration.ofMinutes(5);
    static final Duration MESSAGES_TTL = Duration.ofMinutes(5);
    /** 화면에 보내는 알림의 서비스 이름. 본인에게만 간다. */
    public static final String EVENT_PROVIDER = "SLACK_DM";
    /** 새 메시지로 볼 이벤트 subtype (null은 일반 메시지) */
    private static final Set<String> NEW_MESSAGE_SUBTYPES = Set.of("file_share", "me_message", "thread_broadcast");
    private static final Duration ME_TTL = Duration.ofMinutes(30);

    private final SlackApiClient client;
    private final SlackDirectory directory;
    private final IntegrationService integrations;
    private final WorkspaceAccessChecker access;
    private final WorkspaceEventService workspaceEvents;

    private record Cached<T>(T value, Instant at) {
        boolean fresh(Duration ttl) {
            return at.plus(ttl).isAfter(Instant.now());
        }
    }

    /** 팀원별 DM 목록 (워크스페이스:사용자) */
    private final Map<String, Cached<List<Conversation>>> lists = new ConcurrentHashMap<>();
    /** DM 대화별 메시지 (워크스페이스:사용자:대화) */
    private final Map<String, Cached<List<Message>>> histories = new ConcurrentHashMap<>();
    /** 팀원별 내 Slack 사용자 ID */
    private final Map<String, Cached<String>> slackIds = new ConcurrentHashMap<>();

    /** 내 DM 대화 목록 (상대 이름순). 대화마다 Slack에서 아직 안 읽은 상대 메시지를 함께 준다. */
    public List<Conversation> getConversations(Long workspaceId, Long userId) {
        String token = myToken(workspaceId, userId);
        String key = workspaceId + ":" + userId;
        Cached<List<Conversation>> cached = lists.get(key);
        if (cached != null && cached.fresh(LIST_TTL)) {
            return cached.value();
        }
        Map<String, Conversation> previous = new HashMap<>();
        if (cached != null) {
            cached.value().forEach(c -> previous.put(c.channelId(), c));
        }
        try {
            List<DirectMessage> dms = client.getDirectMessages(token);
            Map<String, SlackUser> users = directory.getUsers(workspaceId, token,
                    dms.stream().map(DirectMessage::userId).toList());
            List<Conversation> result = dms.stream()
                    .filter(dm -> !"USLACKBOT".equals(dm.userId()))
                    .map(dm -> withReadState(token, new Conversation(dm.id(), dm.userId(),
                            nameOf(users.get(dm.userId()), dm.userId()), 0, null), previous.get(dm.id())))
                    .sorted(Comparator.comparing(Conversation::name))
                    .toList();
            lists.put(key, new Cached<>(result, Instant.now()));
            return result;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.SLACK_RATE_LIMITED && cached != null) {
                return cached.value();
            }
            throw e;
        }
    }

    /**
     * Slack이 표시하는 안 읽은 수와 마지막으로 읽은 위치를 붙인다. 대화 기록은 읽지 않는다(한도가 매우 낮다).
     * 확인에 실패하면 직전에 확인한 결과를 쓴다. (목록 자체는 보여 준다)
     */
    private Conversation withReadState(String token, Conversation c, Conversation previous) {
        try {
            SlackApiClient.ReadState state = client.getReadState(token, c.channelId());
            return new Conversation(c.channelId(), c.userId(), c.name(), state.unreadCount(), state.lastRead());
        } catch (BusinessException e) {
            log.info("Slack DM 안 읽은 수 확인 실패, 직전 결과 사용: error={}", e.getErrorCode());
            return previous == null ? c
                    : new Conversation(c.channelId(), c.userId(), c.name(), previous.unreadCount(), previous.lastRead());
        }
    }

    /** DM 대화의 최근 메시지 (시간순) */
    public List<Message> getMessages(Long workspaceId, Long userId, String channelId) {
        requireDm(channelId);
        String token = myToken(workspaceId, userId);
        String key = workspaceId + ":" + userId + ":" + channelId;
        Cached<List<Message>> cached = histories.get(key);
        if (cached != null && cached.fresh(MESSAGES_TTL)) {
            return cached.value();
        }
        try {
            String me = mySlackId(workspaceId + ":" + userId, token);
            LocalDateTime now = LocalDateTime.now(SlackApiClient.ZONE);
            List<SlackMessage> messages = client.getDirectMessageHistory(token, channelId,
                    now.minusDays(RECENT_DAYS), now.plusMinutes(1), null).messages();
            Map<String, SlackUser> users = directory.getUsers(workspaceId, token,
                    messages.stream().map(SlackMessage::userId).filter(Objects::nonNull).toList());
            List<Message> result = messages.stream()
                    .filter(m -> m.text() != null && !m.text().isBlank())
                    .sorted(Comparator.comparing(SlackMessage::ts))
                    .map(m -> toMessage(workspaceId, m, users, me))
                    .toList();
            histories.put(key, new Cached<>(result, Instant.now()));
            return result;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.SLACK_RATE_LIMITED && cached != null) {
                return cached.value();
            }
            throw e;
        }
    }

    /** DM에 답장한다. 내 Slack 계정으로 보낸다. */
    public Message send(Long workspaceId, Long userId, String channelId, String text) {
        requireDm(channelId);
        String token = myToken(workspaceId, userId);
        String me = mySlackId(workspaceId + ":" + userId, token);
        SlackMessage posted = client.postMessage(token, channelId, null, text);
        Map<String, SlackUser> users = directory.getUsers(workspaceId, token, List.of(me));
        SlackMessage withAuthor = posted.userId() != null ? posted
                : new SlackMessage(posted.ts(), posted.threadTs(), me, posted.text(), posted.replyCount(),
                        posted.latestReplyTs(), posted.createdAt(), posted.editedAt(), posted.files(), posted.subtype());
        Message sent = toMessage(workspaceId, withAuthor, users, me);
        // 보낸 메시지를 기억한 대화에 바로 붙인다. (Slack을 다시 부르지 않는다)
        histories.computeIfPresent(workspaceId + ":" + userId + ":" + channelId, (key, cached) -> {
            if (cached.value().stream().anyMatch(m -> m.ts().equals(sent.ts()))) {
                return cached;
            }
            List<Message> next = new ArrayList<>(cached.value());
            next.add(sent);
            return new Cached<>(List.copyOf(next), cached.at());
        });
        return sent;
    }

    private Message toMessage(Long workspaceId, SlackMessage m, Map<String, SlackUser> users, String me) {
        return new Message(m.ts(), m.userId(), nameOf(users.get(m.userId()), m.userId()),
                directory.renderMentions(workspaceId, m.text()), m.createdAt(), me != null && me.equals(m.userId()));
    }

    // ===================== 웹훅 (Slack Events API의 message.im) =====================

    /**
     * Slack이 보낸 DM 메시지 이벤트를 반영한다. Slack API를 다시 부르지 않고 이벤트 내용으로 기억한 목록·메시지를 고친다.
     * 대화 당사자 중 ToGather에 Slack을 연결한 사람의 화면에만 알린다.
     */
    public void onDirectMessageEvent(Map<String, Object> payload) {
        Map<String, Object> event = obj(payload, "event");
        String channelId = str(event, "channel");
        String teamId = str(payload, "team_id");
        if (channelId == null || !channelId.matches("D[A-Z0-9]+")) {
            return;
        }
        String subtype = str(event, "subtype");
        String sender = str(event, "user");
        String threadTs = str(event, "thread_ts");
        String ts = str(event, "ts");
        boolean newMessage = (subtype == null || NEW_MESSAGE_SUBTYPES.contains(subtype)) && ts != null
                && (threadTs == null || threadTs.equals(ts));

        // 이 DM의 당사자: 이벤트를 받은 사람(authorizations), 보낸 사람, 그리고 이 대화를 목록에 가진 사람
        Map<ConnectionOwner, String> owners = new LinkedHashMap<>();
        Set<String> slackUsers = new LinkedHashSet<>();
        list(payload, "authorizations").forEach(a -> slackUsers.add(str(a, "user_id")));
        slackUsers.add(sender);
        slackUsers.add(str(obj(event, "message"), "user"));
        if (teamId != null) {
            for (String slackUser : slackUsers) {
                if (slackUser == null) {
                    continue;
                }
                integrations.findOwnersByExternalAccount(Provider.SLACK, teamId + ":" + slackUser)
                        .forEach(owner -> owners.putIfAbsent(owner, slackUser));
            }
        }
        lists.forEach((key, cached) -> {
            if (cached.value().stream().anyMatch(c -> c.channelId().equals(channelId))) {
                String[] parts = key.split(":");
                owners.putIfAbsent(new ConnectionOwner(Long.valueOf(parts[0]), Long.valueOf(parts[1])), null);
            }
        });

        for (Map.Entry<ConnectionOwner, String> entry : owners.entrySet()) {
            ConnectionOwner owner = entry.getKey();
            String userKey = owner.workspaceId() + ":" + owner.userId();
            Cached<String> knownMe = slackIds.get(userKey);
            String me = entry.getValue() != null ? entry.getValue() : knownMe == null ? null : knownMe.value();
            String historyKey = userKey + ":" + channelId;
            if (newMessage) {
                appendMessage(owner, historyKey, event, me);
                if (me != null && sender != null && !sender.equals(me)) {
                    addUnread(userKey, channelId);
                } else if (me == null) {
                    lists.remove(userKey); // 누가 보냈는지 가릴 수 없으면 다음에 새로 읽는다.
                }
            } else {
                histories.remove(historyKey); // 수정·삭제: 다음에 열 때 새로 읽는다.
            }
            workspaceEvents.publishToUser(owner.workspaceId(), owner.userId(), EVENT_PROVIDER, channelId);
        }
    }

    private void appendMessage(ConnectionOwner owner, String historyKey, Map<String, Object> event, String me) {
        Cached<List<Message>> cached = histories.get(historyKey);
        if (cached == null) {
            return;
        }
        String ts = str(event, "ts");
        if (cached.value().stream().anyMatch(m -> m.ts().equals(ts))) {
            return;
        }
        String sender = str(event, "user");
        String name = sender;
        try {
            String token = integrations.findAccessToken(owner.workspaceId(), owner.userId(), Provider.SLACK).orElse(null);
            if (token != null && sender != null) {
                name = nameOf(directory.getUsers(owner.workspaceId(), token, List.of(sender)).get(sender), sender);
            }
        } catch (BusinessException e) {
            log.info("Slack DM 보낸 사람 이름 확인 실패: error={}", e.getErrorCode());
        }
        Message message = new Message(ts, sender, name == null ? "알 수 없음" : name,
                directory.renderMentions(owner.workspaceId(), str(event, "text")), SlackApiClient.toLocal(ts),
                me != null && me.equals(sender));
        List<Message> next = new ArrayList<>(cached.value());
        next.add(message);
        next.sort(Comparator.comparing(m -> new BigDecimal(m.ts())));
        histories.put(historyKey, new Cached<>(List.copyOf(next), cached.at()));
    }

    private void addUnread(String userKey, String channelId) {
        Cached<List<Conversation>> cached = lists.get(userKey);
        if (cached == null) {
            return;
        }
        if (cached.value().stream().noneMatch(c -> c.channelId().equals(channelId))) {
            lists.remove(userKey); // 새로 생긴 DM: 다음에 목록을 새로 읽는다.
            return;
        }
        List<Conversation> next = cached.value().stream()
                .map(c -> !c.channelId().equals(channelId) ? c
                        : new Conversation(c.channelId(), c.userId(), c.name(), c.unreadCount() + 1, c.lastRead()))
                .toList();
        lists.put(userKey, new Cached<>(next, cached.at()));
    }

    /** 내 Slack 사용자 ID (내가 보낸 메시지 표시용). 자주 바뀌지 않아 오래 기억한다. */
    private String mySlackId(String key, String token) {
        Cached<String> cached = slackIds.get(key);
        if (cached != null && cached.fresh(ME_TTL)) {
            return cached.value();
        }
        String id = client.authTest(token).userId();
        slackIds.put(key, new Cached<>(id, Instant.now()));
        return id;
    }

    private String myToken(Long workspaceId, Long userId) {
        access.checkMember(workspaceId, userId);
        return integrations.findAccessToken(workspaceId, userId, Provider.SLACK)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONNECTION_NOT_FOUND));
    }

    private static void requireDm(String channelId) {
        if (channelId == null || !channelId.matches("D[A-Z0-9]+")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private static String nameOf(SlackUser user, String fallback) {
        return user != null && user.name() != null ? user.name() : fallback == null ? "알 수 없음" : fallback;
    }
}
