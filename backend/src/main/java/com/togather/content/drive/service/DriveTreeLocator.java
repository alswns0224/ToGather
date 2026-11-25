package com.togather.content.drive.service;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.client.DriveApiClient;
import com.togather.content.drive.client.DriveApiClient.DriveItem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 연동한 Drive 폴더(루트) 아래의 폴더 구조를 따라간다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriveTreeLocator {

    private static final int MAX_DEPTH = 32;

    private final DriveApiClient driveApiClient;

    /** id가 연동 폴더 안(연동 폴더 자신 포함)에 있으면 그 연동 폴더 ID. 휴지통이나 다른 곳에 있으면 빈 값 */
    public Optional<String> rootOf(String token, String id, Collection<String> roots) {
        String current = id;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth <= MAX_DEPTH && current != null; depth++) {
            if (roots.contains(current)) {
                return Optional.of(current);
            }
            if (!visited.add(current)) {
                return Optional.empty();
            }
            DriveItem item;
            try {
                item = driveApiClient.getItem(token, current);
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                    return Optional.empty();
                }
                throw e;
            }
            if (item == null || item.trashed() || item.parents().isEmpty()) {
                return Optional.empty();
            }
            current = item.parents().getFirst();
        }
        return Optional.empty();
    }

    /** 시작 폴더들과 그 아래 모든 항목. limit개를 넘으면 거기까지만 돌려준다. */
    public List<DriveItem> collect(String token, Collection<String> startIds, int limit) {
        List<DriveItem> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> folders = new ArrayDeque<>();
        for (String id : startIds) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            try {
                DriveItem start = driveApiClient.getItem(token, id);
                if (start != null && !start.trashed()) {
                    result.add(start);
                    folders.add(id);
                }
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                    throw e;
                }
            }
        }
        while (!folders.isEmpty() && result.size() < limit) {
            for (DriveItem child : driveApiClient.listChildren(token, folders.poll())) {
                if (!seen.add(child.id())) {
                    continue;
                }
                result.add(child);
                if (child.isFolder()) {
                    folders.add(child.id());
                }
                if (result.size() >= limit) {
                    log.warn("Drive 폴더 항목이 많아 {}개까지만 확인", limit);
                    break;
                }
            }
        }
        return result;
    }
}
