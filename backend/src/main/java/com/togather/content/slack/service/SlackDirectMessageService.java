package com.togather.content.slack.service;

import com.togather.common.enums.ContentType;
import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.content.service.ContentRefService.ContentRefUpsert;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.content.slack.client.SlackApiClient.DirectMessagePage;
import com.togather.content.slack.client.SlackApiClient.SlackMessage;
import com.togather.integration.service.ConnectionOperationRegistry;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.OwnAccess;
import com.togather.sync.domain.SyncState;
import com.togather.sync.service.SyncStateService;
import com.togather.common.auth.WorkspaceAccessChecker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 개인 1:1 DM 수집. 팀 채널·색인·워크스페이스 이벤트 경로를 사용하지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackDirectMessageService {
    static final int PAGE_BUDGET = 6;
    private static final Duration REFRESH = Duration.ofMinutes(5);
    private static final Duration CONTINUE_INTERVAL = Duration.ofMinutes(1);
    private static final String PARTIAL = "일부 Slack DM은 다음 동기화에서 이어서 수집합니다. 잠시 후 새 후보 찾기를 눌러 주세요.";
    private static final Set<String> MESSAGE_SUBTYPES = Set.of("file_share", "me_message", "bot_message", "thread_broadcast", "tombstone");
    private final SlackApiClient client;
    private final SlackDirectory directory;
    private final IntegrationService integrations;
    private final ConnectionOperationRegistry operations;
    private final ContentRefService contents;
    private final ContentRefRepository references;
    private final SyncStateService states;
    private final WorkspaceAccessChecker access;
    private final Set<Owner> running = ConcurrentHashMap.newKeySet();

    public record CollectionResult(List<ContentRef> messages, List<String> warnings) { }
    private record Owner(Long workspaceId, Long userId) { }
    private record Job(String channelId, String threadTs) {
        String key() { return threadTs == null ? channelId : channelId + "/" + threadTs; }
    }
    private record PageCursor(LocalDateTime snapshot, String next, Set<String> seen) { }

    public CollectionResult collectForAssistant(Long workspaceId, Long userId) {
        access.checkMember(workspaceId, userId);
        Owner owner = new Owner(workspaceId, userId);
        try (var scope = operations.openScope()) {
            OwnAccess own;
            try {
                own = integrations.findOwnAccess(workspaceId, userId, Provider.SLACK).orElse(null);
                if (own == null) return result(List.of(), "Slack DM을 살펴보려면 본인 Slack 계정을 연결해 주세요.");
                Set<String> scopes = Set.copyOf(List.of((own.scope() == null ? "" : own.scope()).split("[,\\s]+")));
                if (!scopes.containsAll(Set.of("im:read", "im:history"))) {
                    return result(List.of(), "Slack DM 읽기 권한이 없습니다. 연동 관리에서 본인 Slack 계정을 다시 연결해 주세요.");
                }
                if (own.externalAccountId() == null || own.externalAccountId().isBlank()) {
                    return result(List.of(), "Slack 계정 정보를 확인할 수 없습니다. 본인 계정을 다시 연결해 주세요.");
                }
            } catch (BusinessException error) { return result(List.of(), warning(error)); }
            String target = accountTarget(own);
            String originPrefix = "dm:" + userId + ":" + target.substring("slack-dm:".length()) + ":";
            if (!running.add(owner)) return result(saved(workspaceId, userId, originPrefix), "Slack DM을 동기화 중입니다. 잠시 후 다시 확인해 주세요.");
            try {
                SyncState account = states.getOrCreate(workspaceId, Provider.SLACK, target, userId);
                LocalDateTime now = LocalDateTime.now(SlackApiClient.ZONE);
                List<String> warnings = new ArrayList<>();
                if (shouldWait(account, now)) {
                    if (account.getLastError() != null) warnings.add(storedWarning(account.getLastError()));
                    else if (isPending(account)) warnings.add(PARTIAL);
                } else {
                    try { synchronize(workspaceId, userId, own, target, originPrefix, account, now, warnings); }
                    catch (RuntimeException error) {
                        String code = error instanceof BusinessException be ? be.getErrorCode().name() : "EXTERNAL_API_ERROR";
                        if (error instanceof SlackApiClient.RateLimitException limit) {
                            code += ":" + now.plusSeconds(Math.min(limit.retryAfterSeconds(), 31536000));
                        }
                        states.recordFailure(account.getSyncStateId(), code, now);
                        warnings.add(warning(error));
                        log.warn("개인 DM 수집 실패: workspaceId={}, userId={}, error={}", workspaceId, userId,
                                error instanceof BusinessException be ? be.getErrorCode() : error.getClass().getSimpleName());
                    }
                }
                return new CollectionResult(saved(workspaceId, userId, originPrefix), List.copyOf(warnings));
            } finally { running.remove(owner); }
        }
    }

    /** 한 번 개인 수집을 시작한 사용자만 5분 스케줄러에서 다시 확인한다. */
    public void syncKnownOwners() {
        states.getAllStates().stream().filter(state -> state.getProvider() == Provider.SLACK)
                .filter(state -> state.getOwnerUserId() != null && isAccountTarget(state.getTargetId()))
                .map(state -> new Owner(state.getWorkspaceId(), state.getOwnerUserId())).distinct()
                .forEach(owner -> {
                    try { collectForAssistant(owner.workspaceId(), owner.userId()); }
                    catch (RuntimeException error) {
                        log.info("개인 DM 자동 수집 생략: workspaceId={}, userId={}, error={}", owner.workspaceId(), owner.userId(),
                                error instanceof BusinessException be ? be.getErrorCode() : error.getClass().getSimpleName());
                    }
                });
    }

    private void synchronize(Long workspaceId, Long userId, OwnAccess own, String target, String prefix,
                             SyncState account, LocalDateTime now, List<String> warnings) {
        Set<String> channels = new HashSet<>();
        List<Job> jobs = new ArrayList<>();
        client.getDirectMessages(own.accessToken()).forEach(dm -> { channels.add(dm.id()); jobs.add(new Job(dm.id(), null)); });
        for (SyncState state : states.getStates(workspaceId, Provider.SLACK)) {
            if (!userId.equals(state.getOwnerUserId()) || !state.getTargetId().startsWith(target + ":")) continue;
            String key = state.getTargetId().substring(target.length() + 1);
            String[] parts = key.split("/", 2);
            if (parts.length == 2 && channels.contains(parts[0]) && parts[1].matches("[0-9]+\\.[0-9]+")
                    && SlackApiClient.toLocal(parts[1]).isAfter(now.minusDays(14))) jobs.add(new Job(parts[0], parts[1]));
        }
        jobs.sort(Comparator.comparing(Job::key));
        String previous = account.getSyncCursor() == null ? "" : account.getSyncCursor().split("\\|", 2)[0];
        List<Job> ordered = new ArrayList<>();
        jobs.stream().filter(job -> job.key().compareTo(previous) > 0).forEach(ordered::add);
        jobs.stream().filter(job -> job.key().compareTo(previous) <= 0).forEach(ordered::add);
        int fetched = 0;
        boolean pending = false;
        boolean failed = false;
        String last = previous;
        for (Job job : ordered) {
            SyncState state = states.getOrCreate(workspaceId, Provider.SLACK, target + ":" + job.key(), userId);
            if (shouldWait(state, now)) { pending |= state.getSyncCursor() != null; continue; }
            if (fetched >= PAGE_BUDGET) { pending = true; continue; }
            fetched++;
            last = job.key();
            try { pending |= synchronizePage(workspaceId, userId, own, prefix, target, job, state, now); }
            catch (SlackApiClient.ConversationNotFoundException unreadable) {
                // 목록에는 있지만 기록을 읽을 수 없는 DM(비활성 사용자·시스템 봇 등)은 실패로 세지 않고 건너뛴다.
                states.recordSuccess(state.getSyncStateId(), now, null, now);
            }
            catch (RuntimeException error) {
                states.recordFailure(state.getSyncStateId(), error instanceof BusinessException be
                        ? be.getErrorCode().name() : "EXTERNAL_API_ERROR", now);
                if (error instanceof SlackApiClient.RateLimitException) throw error;
                failed = true;
            }
        }
        states.recordSuccess(account.getSyncStateId(), now, last + (pending ? "|pending" : ""), now);
        if (failed) {
            states.recordFailure(account.getSyncStateId(), "SLACK_DM_PARTIAL_FAILURE", now);
            warnings.add("일부 Slack DM을 가져오지 못했습니다. 기존 메시지로 후보를 찾았으며 다음 동기화에서 다시 시도합니다.");
        }
        if (pending) warnings.add(PARTIAL);
    }

    private boolean synchronizePage(Long workspaceId, Long userId, OwnAccess own, String prefix, String target,
                                    Job job, SyncState state, LocalDateTime now) {
        PageCursor page = decode(state.getSyncCursor(), now);
        LocalDateTime oldest = page.snapshot().minusDays(14);
        if (state.getLastSyncedAt() != null && state.getLastSyncedAt().minusDays(3).isAfter(oldest)) {
            oldest = state.getLastSyncedAt().minusDays(3);
        }
        DirectMessagePage response = job.threadTs() == null
                ? client.getDirectMessageHistory(own.accessToken(), job.channelId(), oldest, page.snapshot(), page.next())
                : client.getDirectMessageReplies(own.accessToken(), job.channelId(), job.threadTs(), oldest, page.snapshot(), page.next());
        if (response == null) throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        var users = directory.getUsers(workspaceId, own.accessToken(), response.messages().stream().map(SlackMessage::userId).toList());
        boolean discoveredThread = false;
        Long parentId = job.threadTs() == null ? null : references.findByWorkspaceIdAndProviderAndOriginId(
                workspaceId, Provider.SLACK, prefix + job.channelId() + ":" + job.threadTs())
                .filter(ref -> userId.equals(ref.getOwnerUserId())).map(ContentRef::getContentRefId).orElse(null);
        if (job.threadTs() != null && parentId == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        for (SlackMessage message : response.messages()) {
            if (message.ts() == null || message.createdAt() == null) continue;
            if (job.threadTs() == null && message.isReply()) continue;
            if (job.threadTs() != null && !job.threadTs().equals(message.threadTs())) continue;
            if (job.threadTs() != null && job.threadTs().equals(message.ts())) continue;
            if (message.subtype() != null && !MESSAGE_SUBTYPES.contains(message.subtype())) continue;
            if (message.createdAt().isBefore(oldest) || message.createdAt().isAfter(page.snapshot())) continue;
            String originId = prefix + job.channelId() + ":" + message.ts();
            var author = users.get(message.userId());
            var saved = contents.upsert(new ContentRefUpsert(workspaceId, userId, Provider.SLACK, ContentType.MESSAGE,
                    originId, job.channelId(), parentId, "Slack DM", SlackMessageFormat.toContent(message.text(), message.files()),
                    directory.permalink(workspaceId, own.accessToken(), job.channelId(), message.ts(), message.threadTs()),
                    message.createdAt(), message.editedAt() == null ? message.createdAt() : message.editedAt(),
                    message.userId(), author == null ? null : author.name()));
            if ("tombstone".equals(message.subtype())) contents.markOriginDeleted(saved.contentRefId());
            else page.seen().add(message.ts());
            if (job.threadTs() == null && message.hasReplies()) {
                SyncState thread = states.getOrCreate(workspaceId, Provider.SLACK, target + ":" + job.channelId() + "/" + message.ts(), userId);
                discoveredThread |= thread.getLastSyncedAt() == null || thread.getSyncCursor() != null;
            }
        }
        if (response.nextCursor() == null) {
            for (ContentRef ref : references.findPrivateSlackMessages(workspaceId, userId, prefix + "%", oldest)) {
                if (job.channelId().equals(ref.getChannelId()) && java.util.Objects.equals(parentId, ref.getParentRefId())
                        && !ref.getCreatedAt().isAfter(page.snapshot())
                        && !page.seen().contains(ref.getOriginId().substring(ref.getOriginId().lastIndexOf(':') + 1))) {
                    contents.markOriginDeleted(ref.getContentRefId());
                }
            }
        }
        states.recordSuccess(state.getSyncStateId(), response.nextCursor() == null ? page.snapshot() : state.getLastSyncedAt(),
                response.nextCursor() == null ? null : encode(page, response.nextCursor()), now);
        return response.nextCursor() != null || discoveredThread;
    }

    private List<ContentRef> saved(Long workspaceId, Long userId, String prefix) {
        return references.findPrivateSlackMessages(workspaceId, userId, prefix + "%", LocalDateTime.now(SlackApiClient.ZONE).minusDays(14));
    }

    static String accountTarget(OwnAccess own) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(own.externalAccountId().getBytes(StandardCharsets.UTF_8))).substring(0, 24);
            return "slack-dm:" + own.connectionId() + ":" + digest;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static boolean isAccountTarget(String target) { return target != null && target.matches("slack-dm:[0-9]+:[0-9a-f]{24}"); }
    private static boolean isPending(SyncState state) { return state.getSyncCursor() != null && state.getSyncCursor().endsWith("|pending"); }

    private static boolean shouldWait(SyncState state, LocalDateTime now) {
        if (state.getLastAttemptAt() == null) return false;
        if (state.getLastError() != null && state.getLastError().startsWith("SLACK_RATE_LIMITED:")) {
            try { if (LocalDateTime.parse(state.getLastError().substring("SLACK_RATE_LIMITED:".length())).isAfter(now)) return true; }
            catch (RuntimeException ignored) { }
        }
        Duration interval = state.getSyncCursor() != null ? CONTINUE_INTERVAL : REFRESH;
        return state.getLastAttemptAt().plus(interval).isAfter(now);
    }

    private static PageCursor decode(String cursor, LocalDateTime now) {
        if (cursor == null) return new PageCursor(now, null, new LinkedHashSet<>());
        String[] parts = cursor.split("\n", 3);
        if (parts.length != 3) throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        Set<String> seen = new LinkedHashSet<>();
        if (!parts[2].isEmpty()) seen.addAll(List.of(parts[2].split(",")));
        return new PageCursor(LocalDateTime.parse(parts[0]), new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), seen);
    }

    private static String encode(PageCursor page, String next) {
        return page.snapshot() + "\n" + Base64.getUrlEncoder().withoutPadding().encodeToString(next.getBytes(StandardCharsets.UTF_8))
                + "\n" + String.join(",", page.seen());
    }

    private static CollectionResult result(List<ContentRef> messages, String warning) { return new CollectionResult(messages, List.of(warning)); }
    private static String storedWarning(String error) {
        if (error.startsWith("SLACK_RATE_LIMITED")) return "Slack 요청 한도로 DM 수집을 잠시 쉬고 있습니다. 이후 다시 시도해 주세요.";
        return "최근 Slack DM 동기화에 실패했습니다. 가져온 메시지로 후보를 찾으며 이후 다시 수집합니다.";
    }
    private static String warning(RuntimeException error) {
        if (error instanceof BusinessException be && (be.getErrorCode() == ErrorCode.REAUTH_REQUIRED
                || be.getErrorCode() == ErrorCode.SLACK_SCOPE_REQUIRED)) return "Slack DM을 읽으려면 본인 Slack 계정을 다시 연결해 주세요.";
        if (error instanceof SlackApiClient.RateLimitException) return storedWarning("SLACK_RATE_LIMITED");
        return "Slack DM을 가져오지 못했습니다. 기존에 수집한 메시지와 다른 출처로 후보를 찾습니다.";
    }
}
