package com.togather.content.notion.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.response.ApiResponse;
import com.togather.content.dto.ContentRefDTO;
import com.togather.content.notion.dto.NotionDatabaseResponse;
import com.togather.content.notion.dto.NotionPageCreateRequest;
import com.togather.content.notion.dto.NotionPageDetailResponse;
import com.togather.content.notion.dto.NotionPageResponse;
import com.togather.content.notion.dto.NotionPageUpdateRequest;
import com.togather.content.notion.service.NotionContentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** C-03-03 NotionContentController (UI-05) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/notion")
public class NotionContentController {

    private final NotionContentService notionContentService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    @GetMapping("/databases")
    public ApiResponse<List<NotionDatabaseResponse>> getDatabases(@LoginUser Long userId,
                                                                  @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(notionContentService.getDatabases(workspaceId, userId));
    }

    @GetMapping("/pages")
    public ApiResponse<List<NotionPageResponse>> getContents(@LoginUser Long userId, @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(notionContentService.getPages(workspaceId));
    }

    @GetMapping("/pages/{contentRefId}")
    public ApiResponse<NotionPageDetailResponse> getContentDetail(@LoginUser Long userId,
                                                                  @PathVariable Long workspaceId,
                                                                  @PathVariable Long contentRefId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(notionContentService.getPage(workspaceId, userId, contentRefId));
    }

    @PostMapping("/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContentRefDTO> createContent(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                    @Valid @RequestBody NotionPageCreateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(notionContentService.createContent(workspaceId, userId, request.title(), request.body(),
                request.databaseId()));
    }

    @PatchMapping("/pages/{contentRefId}")
    public ApiResponse<ContentRefDTO> updateContent(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                    @PathVariable Long contentRefId,
                                                    @Valid @RequestBody NotionPageUpdateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(notionContentService.updateContent(workspaceId, userId, contentRefId, request.title(),
                request.body()));
    }

    @DeleteMapping("/pages/{contentRefId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContent(@LoginUser Long userId, @PathVariable Long workspaceId,
                              @PathVariable Long contentRefId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        notionContentService.deleteContent(workspaceId, userId, contentRefId);
    }
}
