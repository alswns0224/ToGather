package com.togather.content.access;

import com.togather.common.enums.Provider;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.notion.client.NotionApiClient;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.slack.client.SlackApiClient;
import com.togather.integration.service.IntegrationService;
import com.togather.integration.service.IntegrationService.OwnAccess;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 팀 자료(Slack 채널 · Notion 페이지·데이터베이스 · Drive 폴더)를 팀원마다 원본 서비스 권한대로 보여 준다.
 *
 * <p>팀이 고른 연동 대상의 내용은 한 번만 수집해 워크스페이스에 저장하지만, 보는 것은 원본에서 볼 수 있는 사람만 본다.
 * <ul>
 *   <li>그 팀원이 이 워크스페이스에서 자기 계정으로 그 서비스를 연동했어야 한다. (개인 연동)</li>
 *   <li>그 계정으로 연동 대상에 접근할 수 있어야 한다. Slack은 채널 멤버, Notion은 페이지·데이터베이스 열람, Drive는 폴더 열람.</li>
 * </ul>
 * 원본 확인은 외부 API를 부르므로 팀원·서비스별로 잠시 저장해 두고 쓴다. 확인이 일시적으로 실패하면 직전 결과를 쓰고,
 * 직전 결과가 없으면 보여 주지 않는다. (권한이 확인되지 않은 자료를 내보내지 않는다)
 */
@Slf4j
@Service
public class ContentAccessService {

    /** 팀원마다 따로 연동해 원본 권한대로 보는 팀 자료 서비스 */
    public static final Set<Provider> MEMBER_SCOPED = Set.of(Provider.SLACK, Provider.NOTION, Provider.GOOGLE_DRIVE);

    /** 원본에서 내보내지면 이 시간 안에 ToGather에서도 안 보인다. (Slack 채널 이벤트를 받으면 바로) */
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String NOTION_PAGE_PREFIX = "page:";

    private final IntegrationService integrationService;
    private final SlackApiClient slackApiClient;
    private final NotionApiClient notionApiClient;
    private final DriveApiClient driveApiClient;
    private final ContentRefRepository contentRefRepository;
    private boolean enabled; // 테스트에서 켜고 끈다

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(Long connectionId, Set<String> visible, Instant checkedAt) {
    }

    public ContentAccessService(IntegrationService integrationService, SlackApiClient slackApiClient,
                                NotionApiClient notionApiClient, DriveApiClient driveApiClient,
                                ContentRefRepository contentRefRepository,
                                @Value("${togather.member-access.enabled:true}") boolean enabled) {
        this.integrationService = integrationService;
        this.slackApiClient = slackApiClient;
        this.notionApiClient = notionApiClient;
        this.driveApiClient = driveApiClient;
        this.contentRefRepository = contentRefRepository;
        this.enabled = enabled;
    }

    // ===================== 조회 =====================

    /** 이 팀원이 내 계정으로 이 서비스를 연동했는지 */
    public boolean hasOwnConnection(Long workspaceId, Long userId, Provider provider) {
        return !enabled || !MEMBER_SCOPED.contains(provider) || ownAccess(workspaceId, userId, provider).isPresent();
    }

    /** 팀이 고른 연동 대상 중 이 팀원이 원본에서 볼 수 있는 것 (Slack 채널 ID, Notion 대상 ID, Drive 폴더 ID) */
    public Set<String> visibleTargets(Long workspaceId, Long userId, Provider provider) {
        List<String> targets = integrationService.getConnectionTargets(workspaceId, provider);
        if (!enabled || !MEMBER_SCOPED.contains(provider)) {
            return new LinkedHashSet<>(targets);
        }
        Optional<OwnAccess> own = ownAccess(workspaceId, userId, provider);
        if (own.isEmpty()) {
            return Set.of();
        }
        String key = key(workspaceId, userId, provider);
        Entry cached = cache.get(key);
        if (cached != null && cached.connectionId().equals(own.get().connectionId())
                && cached.checkedAt().plus(TTL).isAfter(Instant.now())) {
            return intersect(cached.visible(), targets);
        }
        try {
            Set<String> visible = check(provider, own.get().accessToken(), targets);
            cache.put(key, new Entry(own.get().connectionId(), visible, Instant.now()));
            return visible;
        } catch (RuntimeException e) {
            ErrorCode code = e instanceof BusinessException be ? be.getErrorCode() : ErrorCode.EXTERNAL_API_ERROR;
            if (!TRANSIENT.contains(code)) {
                // 토큰이 막혔다(워크스페이스·팀에서 내보내짐, 권한 회수) → 바로 아무것도 보여 주지 않는다.
                log.info("원본 권한 확인 거절, 숨김: workspaceId={}, userId={}, provider={}, error={}", workspaceId, userId,
                        provider, code);
                cache.put(key, new Entry(own.get().connectionId(), Set.of(), Instant.now()));
                return Set.of();
            }
            log.info("원본 권한 확인 일시 실패, 직전 결과 사용: workspaceId={}, userId={}, provider={}, error={}",
                    workspaceId, userId, provider, code);
            return cached != null && cached.connectionId().equals(own.get().connectionId())
                    ? intersect(cached.visible(), targets) : Set.of();
        }
    }

    /** 이 팀원이 이 자료를 볼 수 있는지. 개인 자료(DM·메일·개인 일정)는 주인만 본다. */
    public boolean canView(Long workspaceId, Long userId, ContentRef ref) {
        if (ref == null || !ref.getWorkspaceId().equals(workspaceId)) {
            return false;
        }
        if (ref.getOwnerUserId() != null) {
            return ref.getOwnerUserId().equals(userId);
        }
        if (!enabled || !MEMBER_SCOPED.contains(ref.getProvider())) {
            return true;
        }
        if (ref.getChannelId() == null) {
            return hasOwnConnection(workspaceId, userId, ref.getProvider());
        }
        return visibleTargets(workspaceId, userId, ref.getProvider()).contains(ref.getChannelId());
    }

    /** 목록 거르기: 볼 수 있는 자료만 남긴다. (서비스별 확인은 한 번씩만 한다) */
    public <T> List<T> filter(Long workspaceId, Long userId, Collection<T> items, Function<T, ContentRef> toRef) {
        if (!enabled) {
            return List.copyOf(items);
        }
        Map<Provider, Set<String>> visible = new HashMap<>();
        Map<Provider, Boolean> own = new HashMap<>();
        return items.stream().filter(item -> {
            ContentRef ref = toRef.apply(item);
            if (ref == null || !ref.getWorkspaceId().equals(workspaceId)) {
                return false;
            }
            if (ref.getOwnerUserId() != null) {
                return ref.getOwnerUserId().equals(userId);
            }
            Provider provider = ref.getProvider();
            if (!MEMBER_SCOPED.contains(provider)) {
                return true;
            }
            if (ref.getChannelId() == null) {
                return own.computeIfAbsent(provider, p -> hasOwnConnection(workspaceId, userId, p));
            }
            return visible.computeIfAbsent(provider, p -> visibleTargets(workspaceId, userId, p))
                    .contains(ref.getChannelId());
        }).toList();
    }

    /** contentRefId 목록 중 볼 수 있는 것 */
    public Set<Long> visibleIds(Long workspaceId, Long userId, Collection<Long> contentRefIds) {
        if (contentRefIds.isEmpty()) {
            return Set.of();
        }
        List<ContentRef> refs = contentRefRepository.findAllById(Set.copyOf(contentRefIds));
        return filter(workspaceId, userId, refs, Function.identity()).stream()
                .map(ContentRef::getContentRefId).collect(Collectors.toSet());
    }

    // ===================== 검사 (막기) =====================

    /** 연동 대상(채널·페이지·폴더)을 볼 수 없으면 막는다. */
    public void requireTarget(Long workspaceId, Long userId, Provider provider, String targetId) {
        if (!enabled || !MEMBER_SCOPED.contains(provider)) {
            return;
        }
        requireOwnConnection(workspaceId, userId, provider);
        if (targetId == null || !visibleTargets(workspaceId, userId, provider).contains(targetId)) {
            throw new BusinessException(ErrorCode.CONTENT_ACCESS_DENIED);
        }
    }

    /** 자료를 볼 수 없으면 막는다. */
    public void requireContent(Long workspaceId, Long userId, ContentRef ref) {
        if (ref != null && enabled && ref.getOwnerUserId() == null && MEMBER_SCOPED.contains(ref.getProvider())) {
            requireOwnConnection(workspaceId, userId, ref.getProvider());
        }
        if (!canView(workspaceId, userId, ref)) {
            throw new BusinessException(ErrorCode.CONTENT_ACCESS_DENIED);
        }
    }

    public void requireContent(Long workspaceId, Long userId, Long contentRefId) {
        requireContent(workspaceId, userId, contentRefRepository.findById(contentRefId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    public void requireOwnConnection(Long workspaceId, Long userId, Provider provider) {
        if (!hasOwnConnection(workspaceId, userId, provider)) {
            throw new BusinessException(ErrorCode.OWN_CONNECTION_REQUIRED);
        }
    }

    /** 네트워크·호출 제한처럼 잠깐 실패한 경우만 직전 결과를 쓴다. 나머지(재인증 필요 등)는 숨긴다. */
    private static final Set<ErrorCode> TRANSIENT = Set.of(ErrorCode.EXTERNAL_API_ERROR, ErrorCode.SLACK_RATE_LIMITED);

    /** 모든 팀원의 확인 결과를 버린다. (원본 멤버가 바뀌었다는 알림을 받았을 때) */
    public void forgetAll() {
        cache.clear();
    }

    /** 연결·해제·대상 변경 뒤 다시 확인하게 한다. */
    public void forget(Long workspaceId, Long userId) {
        cache.keySet().removeIf(k -> k.startsWith(workspaceId + ":" + (userId == null ? "" : userId + ":")));
    }

    // ===================== 원본 확인 =====================

    private Set<String> check(Provider provider, String token, List<String> targets) {
        if (targets.isEmpty()) {
            return Set.of();
        }
        return switch (provider) {
            case SLACK -> {
                Set<String> member = slackApiClient.getMemberChannelIds(token);
                yield targets.stream().filter(member::contains).collect(Collectors.toCollection(LinkedHashSet::new));
            }
            case NOTION -> targets.stream().filter(target -> canOpenNotion(token, target))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            case GOOGLE_DRIVE -> targets.stream().filter(folder -> canOpenDrive(token, folder))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            default -> new LinkedHashSet<>(targets);
        };
    }

    private boolean canOpenNotion(String token, String target) {
        try {
            if (target.startsWith(NOTION_PAGE_PREFIX)) {
                notionApiClient.getPage(token, target.substring(NOTION_PAGE_PREFIX.length()));
            } else {
                notionApiClient.getDatabase(token, target);
            }
            return true;
        } catch (BusinessException denied) {
            if (denied.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND
                    || denied.getErrorCode() == ErrorCode.EXTERNAL_API_ERROR) {
                return false; // Notion은 공유받지 않은 페이지를 404로 알린다.
            }
            throw denied;
        }
    }

    private boolean canOpenDrive(String token, String folderId) {
        try {
            driveApiClient.getFileMetadata(token, folderId);
            return true;
        } catch (BusinessException denied) {
            if (denied.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND
                    || denied.getErrorCode() == ErrorCode.EXTERNAL_API_ERROR) {
                return false; // 공유받지 않은 폴더는 404·403
            }
            throw denied;
        }
    }

    private Optional<OwnAccess> ownAccess(Long workspaceId, Long userId, Provider provider) {
        try {
            return integrationService.findOwnAccess(workspaceId, userId, provider);
        } catch (BusinessException e) {
            return Optional.empty();
        }
    }

    private static Set<String> intersect(Set<String> visible, List<String> targets) {
        return targets.stream().filter(visible::contains).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String key(Long workspaceId, Long userId, Provider provider) {
        return workspaceId + ":" + userId + ":" + provider;
    }
}
