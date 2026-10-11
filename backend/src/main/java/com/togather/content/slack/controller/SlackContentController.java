package com.togather.content.slack.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.response.ApiResponse;
import com.togather.common.response.PageResponse;
import com.togather.content.slack.dto.SlackChannelResponse;
import com.togather.content.slack.dto.SlackMessageRequest;
import com.togather.content.slack.dto.SlackMessageResponse;
import com.togather.content.slack.dto.ThreadDetailResponse;
import com.togather.content.slack.service.SlackContentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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

/** C-03-01 SlackContentController (UI-04). 메시지는 채널 ID와 ts로 식별한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/slack/channels")
public class SlackContentController {

    private final SlackContentService slackContentService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    @GetMapping
    public ApiResponse<List<SlackChannelResponse>> getChannels(@LoginUser Long userId,
                                                               @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(slackContentService.getChannels(workspaceId, userId));
    }

    @GetMapping("/{channelId}/messages")
    public ApiResponse<PageResponse<SlackMessageResponse>> getChannelMessages(@LoginUser Long userId,
                                                                              @PathVariable Long workspaceId,
                                                                              @PathVariable String channelId,
                                                                              Pageable pageable) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(slackContentService.getChannelMessages(workspaceId, userId, channelId, pageable));
    }

    @GetMapping("/{channelId}/messages/{messageTs}/thread")
    public ApiResponse<ThreadDetailResponse> getThreadDetail(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                             @PathVariable String channelId,
                                                             @PathVariable String messageTs) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(slackContentService.getThreadDetail(workspaceId, userId, channelId, messageTs));
    }

    @PostMapping("/{channelId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SlackMessageResponse> createMessage(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                           @PathVariable String channelId,
                                                           @Valid @RequestBody SlackMessageRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(slackContentService.createMessage(workspaceId, userId, channelId, request.text()));
    }

    @PostMapping("/{channelId}/messages/{threadTs}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SlackMessageResponse> createReply(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                         @PathVariable String channelId,
                                                         @PathVariable String threadTs,
                                                         @Valid @RequestBody SlackMessageRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(slackContentService.createReply(workspaceId, userId, channelId, threadTs,
                request.text()));
    }

    @PatchMapping("/{channelId}/messages/{messageTs}")
    public ApiResponse<SlackMessageResponse> updateMessage(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                           @PathVariable String channelId,
                                                           @PathVariable String messageTs,
                                                           @Valid @RequestBody SlackMessageRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(slackContentService.updateMessage(workspaceId, userId, channelId, messageTs,
                request.text()));
    }

    @DeleteMapping("/{channelId}/messages/{messageTs}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMessage(@LoginUser Long userId, @PathVariable Long workspaceId,
                              @PathVariable String channelId, @PathVariable String messageTs) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        slackContentService.deleteMessage(workspaceId, userId, channelId, messageTs);
    }
}
