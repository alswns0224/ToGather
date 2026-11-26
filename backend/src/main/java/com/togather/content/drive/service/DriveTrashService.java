package com.togather.content.drive.service;

import com.togather.auth.dto.UserSummary;
import com.togather.auth.service.UserService;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.Provider;
import com.togather.common.event.WorkspaceEventService;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.domain.ContentRef;
import com.togather.content.domain.ContentRef.DriveFileState;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveItem;
import com.togather.content.drive.domain.DriveTrashEntry;
import com.togather.content.drive.domain.DriveWorkspaceSetting;
import com.togather.content.drive.dto.DriveTrashItem;
import com.togather.content.drive.repository.DriveTrashEntryRepository;
import com.togather.content.drive.repository.DriveWorkspaceSettingRepository;
import com.togather.content.drive.service.DriveOwnerAccess.OwnerToken;
import com.togather.content.repository.ContentRefRepository;
import com.togather.content.service.ContentRefService;
import com.togather.integration.client.GoogleIntegrationClient;
import com.togather.integration.client.OAuthProviderClient.TargetOption;
import com.togather.integration.service.IntegrationService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Drive 휴지통 = 팀장 내 드라이브의 "ToGather 휴지통" 폴더.
 *
 * <p>모든 항목이 팀장 소유라 팀장 토큰으로 옮기고 지운다. 휴지통 폴더는 연동 폴더 밖에 있어서, 옮긴 항목은 공유 폴더에서
 * 받던 권한이 빠져 팀원에게서 사라지고 팀장 Drive에만 남는다. Drive에서 직접 휴지통 폴더로 옮긴 것도 그대로 휴지통으로 보인다.
 * 원래 위치는 drive_trash_entry에 남겨 복원할 때 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriveTrashService {

    private static final Provider PROVIDER = Provider.GOOGLE_DRIVE;

    private final DriveApiClient driveApiClient;
    private final DriveOwnerAccess ownerAccess;
    private final DriveTreeLocator locator;
    private final DriveContentService driveContentService;
    private final DriveTrashEntryRepository entryRepository;
    private final DriveWorkspaceSettingRepository settingRepository;
    private final ContentRefRepository contentRefRepository;
    private final ContentRefService contentRefService;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final WorkspaceEventService workspaceEventService;
    private final UserService userService;
    private final IntegrationService integrationService;
    private final com.togather.content.access.ContentAccessService contentAccess;
    private final JdbcTemplate jdbc;

    public record EmptyResult(int deleted, int failed) {
    }

    // ===================== 휴지통 폴더 =====================

    /**
     * 휴지통 폴더를 확인하고, 없거나 지워졌으면 팀장 내 드라이브 루트에 새로 만든다.
     *
     * <p>휴지통 폴더에는 ToGather 표시(appProperties)를 붙여 연동할 폴더 목록에서 빼고, 이름에 워크스페이스 번호를 넣어
     * 이름이 같은 워크스페이스끼리도 구별한다. 저장한 ID를 잃었어도 표시로 찾아 다시 써서 휴지통이 여러 개 생기지 않게 한다.
     */
    public synchronized String ensureTrashFolder(Long workspaceId, OwnerToken owner) {
        DriveWorkspaceSetting setting = settingRepository.findById(workspaceId)
            .orElseGet(() -> new DriveWorkspaceSetting(workspaceId, owner.userId()));
        String name = trashFolderName(workspaceId);
        Map<String, String> marker = Map.of(
            GoogleIntegrationClient.DRIVE_TRASH_MARKER, "true",
            GoogleIntegrationClient.DRIVE_TRASH_WORKSPACE, workspaceId.toString());

        String current = setting.getTrashFolderId();
        Optional<DriveItem> usable = current == null ? Optional.empty() : usableTrash(owner, current);
        if (usable.isPresent()) {
            refreshTrash(owner, usable.get(), name, marker);
            return current;
        }

        Optional<DriveItem> marked = driveApiClient.findMarkedFolders(owner.accessToken(),
            GoogleIntegrationClient.DRIVE_TRASH_WORKSPACE, workspaceId.toString()).stream().findFirst();
        String id;
        if (marked.isPresent()) {
            refreshTrash(owner, marked.get(), name, marker);
            id = marked.get().id();
        } else {
            id = driveApiClient.createFolder(owner.accessToken(), name, null, marker).id();
            log.info("Drive 휴지통 폴더 생성: workspaceId={}, folderId={}", workspaceId, id);
        }
        setting.changeTrashFolder(id);
        settingRepository.save(setting);
        return id;
    }

    private Optional<DriveItem> usableTrash(OwnerToken owner, String folderId) {
        try {
            DriveItem folder = driveApiClient.getItem(owner.accessToken(), folderId);
            boolean usable = folder.isFolder() && !folder.trashed()
                && (owner.email() == null || folder.isOwnedBy(owner.email()));
            return usable ? Optional.of(folder) : Optional.empty();
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** 이름(워크스페이스 이름이 바뀐 경우)이나 표시가 다르면 맞춘다. */
    private void refreshTrash(OwnerToken owner, DriveItem folder, String name, Map<String, String> marker) {
        boolean marked = marker.entrySet().stream()
            .allMatch(entry -> entry.getValue().equals(folder.appProperties().get(entry.getKey())));
        if (!marked || !name.equals(folder.name())) {
            driveApiClient.updateFolder(owner.accessToken(), folder.id(), name, marker);
        }
    }

    /** 예: "ToGather 휴지통 · 테스트 #1" (#뒤는 워크스페이스 번호) */
    private String trashFolderName(Long workspaceId) {
        return "ToGather 휴지통 · " + workspaceName(workspaceId) + " #" + workspaceId;
    }

    // ===================== 휴지통으로 이동 =====================

    public void trashFile(Long workspaceId, Long userId, Long contentRefId) {
        ContentRef file = contentRefRepository.findById(contentRefId)
            .filter(ref -> workspaceId.equals(ref.getWorkspaceId()) && ref.getProvider() == PROVIDER)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        trash(workspaceId, userId, file.getOriginId());
    }

    /**
     * 연동 폴더 안의 파일·폴더를 휴지통 폴더로 옮긴다. 맨 위 연동 폴더 자신은 옮길 수 없다.
     * 상위 연동 폴더를 고를 때 함께 연동된 하위 폴더는 옮길 수 있고, 옮기면 그 폴더(와 그 아래 연동 폴더)를 연동에서 뺀다.
     */
    public void trash(Long workspaceId, Long userId, String itemId) {
        List<String> roots = driveContentService.getFolders(workspaceId, userId);
        // 볼 수 있는 연동 폴더는 잠깐 저장해 두고 쓰므로 방금 더한 폴더가 빠져 있을 수 있다. 연동 여부는 저장된 목록으로 본다.
        List<String> linked = integrationService.getConnectionTargets(workspaceId, PROVIDER);
        boolean connectedFolder = roots.contains(itemId) || linked.contains(itemId);
        OwnerToken owner = null;
        if (connectedFolder) {
            List<String> others = roots.stream().filter(r -> !r.equals(itemId)).toList();
            owner = ownerAccess.requireOwnerToken(workspaceId);
            DriveItem self = driveApiClient.getItem(owner.accessToken(), itemId);
            boolean nested = !self.parents().isEmpty() && !others.isEmpty()
                && locator.rootOf(owner.accessToken(), self.parents().getFirst(), others).isPresent();
            if (!nested) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "연동한 폴더 자체는 휴지통으로 옮길 수 없습니다.");
            }
        }
        if (owner == null) {
            owner = ownerAccess.requireOwnerToken(workspaceId);
        }
        String trashFolder = ensureTrashFolder(workspaceId, owner);
        if (itemId.equals(trashFolder)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        DriveItem item = driveApiClient.getItem(owner.accessToken(), itemId);
        if (item.trashed() || item.parents().isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String parentId = item.parents().getFirst();
        List<String> parentRoots = roots.stream().filter(r -> !r.equals(itemId)).toList();
        String rootId = locator.rootOf(owner.accessToken(), parentId, parentRoots)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 폴더를 옮기면 그 폴더와 그 안의 연동 폴더는 연동에서 뺀다. (휴지통 폴더는 연동 폴더 밖이라 그대로 두면 동기화가 꼬인다)
        List<String> unlinked = new ArrayList<>();
        if (item.isFolder()) {
            String token = owner.accessToken();
            for (String root : linked) {
                if (root.equals(itemId) || locator.rootOf(token, root, List.of(itemId)).isPresent()) {
                    unlinked.add(root);
                }
            }
        }

        driveApiClient.moveItem(owner.accessToken(), itemId, parentId, trashFolder);
        integrationService.removeDriveTargets(workspaceId, unlinked);

        entryRepository.findByWorkspaceIdAndItemId(workspaceId, itemId).ifPresent(entryRepository::delete);
        entryRepository.save(new DriveTrashEntry(workspaceId, itemId, item.name(), item.isFolder(), rootId, parentId,
            userId, now()));
        markTracked(workspaceId, itemId, DriveFileState.UNAVAILABLE, null);
        workspaceEventService.publish(workspaceId, PROVIDER.name(), rootId);
        log.info("Drive 휴지통 이동: workspaceId={}, itemId={}, folder={}", workspaceId, itemId, item.isFolder());
    }

    // ===================== 복원 =====================

    /** 원래 자리로 되돌린다. 원래 자리가 없어졌거나 휴지통 안에 있으면 연동 폴더 바로 아래로 옮긴다. */
    public void restore(Long workspaceId, Long userId, String itemId) {
        List<String> roots = driveContentService.getFolders(workspaceId, userId);
        if (roots.isEmpty()) {
            throw new BusinessException(ErrorCode.INTEGRATION_TARGET_NOT_SELECTED);
        }
        OwnerToken owner = ownerAccess.requireOwnerToken(workspaceId);
        String trashFolder = ownerAccess.trashFolderId(workspaceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        DriveItem item = driveApiClient.getItem(owner.accessToken(), itemId);
        if (item.trashed() || !item.parents().contains(trashFolder)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Optional<DriveTrashEntry> entry = entryRepository.findByWorkspaceIdAndItemId(workspaceId, itemId);
        String target = entry.map(e -> restoreTarget(owner.accessToken(), e, roots)).orElse(roots.getFirst());

        driveApiClient.moveItem(owner.accessToken(), itemId, trashFolder, target);

        // 연동 폴더 바로 아래로 돌아온 폴더는 그 아래 폴더까지 다시 연동한다. (휴지통으로 옮길 때 연동에서 뺐으므로)
        if (item.isFolder() && roots.contains(target)) {
            integrationService.addDriveTargets(workspaceId, locator.collect(owner.accessToken(), List.of(itemId), 500).stream()
                .filter(DriveItem::isFolder)
                .map(f -> new TargetOption(f.id(), f.name(), f.parents().isEmpty() ? null : f.parents().getFirst()))
                .toList());
            contentAccess.forget(workspaceId, null);
        }

        entry.ifPresent(entryRepository::delete);
        if (roots.contains(target)) {
            markTracked(workspaceId, itemId, DriveFileState.AVAILABLE, target);
        }
        workspaceEventService.publish(workspaceId, PROVIDER.name(),
            roots.contains(target) ? target : entry.map(DriveTrashEntry::getRootFolderId).orElse(target));
        log.info("Drive 휴지통 복원: workspaceId={}, itemId={}", workspaceId, itemId);
    }

    private String restoreTarget(String token, DriveTrashEntry entry, List<String> roots) {
        if (locator.rootOf(token, entry.getOriginParentId(), roots).isPresent()) {
            return entry.getOriginParentId();
        }
        return roots.contains(entry.getRootFolderId()) ? entry.getRootFolderId() : roots.getFirst();
    }

    // ===================== 영구 삭제 =====================

    /** 팀장·관리자만. 휴지통 폴더에 있거나, Google Drive 휴지통에 있는 수집 파일만 지운다. */
    public void deleteForever(Long workspaceId, Long userId, String itemId) {
        workspaceAccessChecker.checkOwner(workspaceId, userId);
        List<String> roots = driveContentService.getFolders(workspaceId, userId);
        OwnerToken owner = ownerAccess.requireOwnerToken(workspaceId);
        Optional<String> trashFolder = ownerAccess.trashFolderId(workspaceId);
        Optional<ContentRef> tracked = contentRefRepository.findByWorkspaceIdAndProviderAndOriginId(
            workspaceId, PROVIDER, itemId);

        DriveItem item;
        try {
            item = driveApiClient.getItem(owner.accessToken(), itemId);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                forget(workspaceId, itemId);
                return;
            }
            throw e;
        }
        boolean inTrashFolder = trashFolder.isPresent() && !item.trashed() && item.parents().contains(trashFolder.get());
        boolean inDriveTrash = item.trashed() && tracked.isPresent() && roots.contains(tracked.get().getChannelId());
        if (!inTrashFolder && !inDriveTrash) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        driveApiClient.deleteItem(owner.accessToken(), itemId);
        forget(workspaceId, itemId);
        tracked.ifPresent(ref -> workspaceEventService.publish(workspaceId, PROVIDER.name(), ref.getChannelId()));
        log.info("Drive 영구 삭제: workspaceId={}, itemId={}", workspaceId, itemId);
    }

    /** 휴지통 폴더를 비운다. 하나가 실패해도 나머지는 계속 지운다. */
    public EmptyResult empty(Long workspaceId, Long userId) {
        workspaceAccessChecker.checkOwner(workspaceId, userId);
        driveContentService.getFolders(workspaceId, userId);
        OwnerToken owner = ownerAccess.requireOwnerToken(workspaceId);
        Optional<String> trashFolder = ownerAccess.trashFolderId(workspaceId);
        if (trashFolder.isEmpty()) {
            return new EmptyResult(0, 0);
        }
        int deleted = 0;
        int failed = 0;
        for (DriveItem item : listTrashFolder(owner.accessToken(), trashFolder.get())) {
            try {
                driveApiClient.deleteItem(owner.accessToken(), item.id());
                forget(workspaceId, item.id());
                deleted++;
            } catch (BusinessException e) {
                failed++;
                log.warn("Drive 휴지통 비우기 실패: workspaceId={}, itemId={}, code={}", workspaceId, item.id(),
                    e.getErrorCode());
            }
        }
        workspaceEventService.publish(workspaceId, PROVIDER.name(), trashFolder.get());
        return new EmptyResult(deleted, failed);
    }

    // ===================== 조회 =====================

    /** 휴지통 폴더의 항목과, Google Drive 휴지통에 있는 수집 파일 */
    public List<DriveTrashItem> list(Long workspaceId, Long userId) {
        List<String> roots = driveContentService.getFolders(workspaceId, userId);
        if (roots.isEmpty()) {
            return List.of();
        }
        List<DriveTrashItem> result = new ArrayList<>();
        Optional<OwnerToken> owner = ownerAccess.findOwnerToken(workspaceId);
        Optional<String> trashFolder = ownerAccess.trashFolderId(workspaceId);
        if (owner.isPresent() && trashFolder.isPresent()) {
            Map<String, DriveTrashEntry> entries = entryRepository.findAllByWorkspaceId(workspaceId).stream()
                .collect(Collectors.toMap(DriveTrashEntry::getItemId, Function.identity(), (a, b) -> b));
            List<DriveItem> items = listTrashFolder(owner.get().accessToken(), trashFolder.get());
            Map<Long, String> names = userNames(entries.values().stream()
                .map(DriveTrashEntry::getTrashedBy).filter(Objects::nonNull).distinct().toList());
            for (DriveItem item : items) {
                DriveTrashEntry entry = entries.get(item.id());
                // 다른 팀원 계정으로만 볼 수 있는 연동 폴더에서 온 항목은 보여 주지 않는다.
                if (entry != null && !roots.contains(entry.getRootFolderId())) {
                    continue;
                }
                Long contentRefId = item.isFolder() ? null : contentRefRepository
                    .findByWorkspaceIdAndProviderAndOriginId(workspaceId, PROVIDER, item.id())
                    .map(ContentRef::getContentRefId).orElse(null);
                result.add(new DriveTrashItem(item.id(), item.name(), item.isFolder(), item.mimeType(),
                    item.fileSize(), entry == null ? item.modifiedAt() : entry.getTrashedAt(),
                    entry == null ? null : entry.getTrashedBy(),
                    entry == null ? null : names.get(entry.getTrashedBy()),
                    contentRefId, DriveTrashItem.TOGATHER));
            }
        }
        List<ContentRefDTO> driveTrash = driveContentService.getTrashFiles(workspaceId, userId);
        // Drive는 휴지통에 넣은 시각을 주지 않는다. ToGather가 처음 본 시각이 있으면 그것을, 없으면 수정 시각을 쓴다.
        Map<Long, LocalDateTime> seenAt = contentRefRepository.findAllById(
                driveTrash.stream().map(ContentRefDTO::contentRefId).toList()).stream()
            .filter(ref -> ref.getDriveTrashedAt() != null)
            .collect(Collectors.toMap(ContentRef::getContentRefId, ContentRef::getDriveTrashedAt));
        for (ContentRefDTO file : driveTrash) {
            result.add(new DriveTrashItem(file.externalId(), file.title(), false, null, file.fileSize(),
                seenAt.getOrDefault(file.contentRefId(), file.lastModifiedAt()), null, null, file.contentRefId(),
                DriveTrashItem.DRIVE));
        }
        result.sort(Comparator.comparing(DriveTrashItem::trashedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private List<DriveItem> listTrashFolder(String token, String trashFolder) {
        try {
            return driveApiClient.listChildren(token, trashFolder);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                return List.of();
            }
            throw e;
        }
    }

    // ===================== 내부 =====================

    /** ToGather가 수집한 파일이면 목록에서 숨기거나(UNAVAILABLE) 다시 보이게(AVAILABLE) 한다. */
    private void markTracked(Long workspaceId, String itemId, DriveFileState state, String folderId) {
        contentRefRepository.findByWorkspaceIdAndProviderAndOriginId(workspaceId, PROVIDER, itemId)
            .ifPresent(ref -> contentRefService.updateDriveLocation(workspaceId, itemId, null,
                folderId != null ? folderId : ref.getChannelId(), state));
    }

    private void forget(Long workspaceId, String itemId) {
        entryRepository.findByWorkspaceIdAndItemId(workspaceId, itemId).ifPresent(entryRepository::delete);
        markTracked(workspaceId, itemId, DriveFileState.UNAVAILABLE, null);
    }

    private Map<Long, String> userNames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userService.getUsers(userIds).stream()
            .collect(Collectors.toMap(UserSummary::userId, UserSummary::name, (a, b) -> a));
    }

    private String workspaceName(Long workspaceId) {
        return jdbc.query("SELECT name FROM workspace WHERE workspace_id = ?",
            rs -> rs.next() ? rs.getString(1) : "워크스페이스", workspaceId);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(DriveApiClient.ZONE);
    }
}
