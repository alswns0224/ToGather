package com.togather.content.drive.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.response.ApiResponse;
import com.togather.content.drive.dto.DriveOwnershipStatus;
import com.togather.content.drive.dto.DriveTrashItem;
import com.togather.content.drive.service.DriveOwnershipService;
import com.togather.content.drive.service.DriveOwnershipService.PassResult;
import com.togather.content.drive.service.DriveTrashService;
import com.togather.content.drive.service.DriveTrashService.EmptyResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drive 휴지통(팀장 Drive의 ToGather 휴지통 폴더)과 팀장 소유권 상태.
 *
 * <p>itemId는 Google Drive 파일·폴더 ID다. 하위 폴더와 그 안의 파일처럼 ToGather가 수집하지 않은 항목도 다룬다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/drive")
public class DriveTrashController {

    private final DriveTrashService trashService;
    private final DriveOwnershipService ownershipService;

    @GetMapping("/trash")
    public ApiResponse<List<DriveTrashItem>> list(@LoginUser Long userId, @PathVariable Long workspaceId) {
        return ApiResponse.ok(trashService.list(workspaceId, userId));
    }

    /** 연동 폴더 안의 파일·폴더를 휴지통으로 옮긴다. */
    @PostMapping("/items/{itemId}/trash")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trash(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable String itemId) {
        trashService.trash(workspaceId, userId, itemId);
    }

    @PostMapping("/trash/{itemId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable String itemId) {
        trashService.restore(workspaceId, userId, itemId);
    }

    /** 영구 삭제 (팀장·관리자) */
    @DeleteMapping("/trash/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForever(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable String itemId) {
        trashService.deleteForever(workspaceId, userId, itemId);
    }

    /** 휴지통 비우기 (팀장·관리자) */
    @DeleteMapping("/trash")
    public ApiResponse<EmptyResult> empty(@LoginUser Long userId, @PathVariable Long workspaceId) {
        return ApiResponse.ok(trashService.empty(workspaceId, userId));
    }

    @GetMapping("/ownership")
    public ApiResponse<DriveOwnershipStatus> ownership(@LoginUser Long userId, @PathVariable Long workspaceId) {
        return ApiResponse.ok(ownershipService.status(workspaceId, userId));
    }

    /** 팀장 소유가 아닌 항목을 지금 팀장에게 넘긴다. (팀장) */
    @PostMapping("/ownership/consolidate")
    public ApiResponse<PassResult> consolidate(@LoginUser Long userId, @PathVariable Long workspaceId) {
        return ApiResponse.ok(ownershipService.consolidateNow(workspaceId, userId));
    }
}
