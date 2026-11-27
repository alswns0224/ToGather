package com.togather.content.slack.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.response.ApiResponse;
import com.togather.content.slack.dto.SlackDmResponse.Conversation;
import com.togather.content.slack.dto.SlackDmResponse.Message;
import com.togather.content.slack.dto.SlackMessageRequest;
import com.togather.content.slack.service.SlackDmService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 내 Slack 1:1 DM 보기·답장 (UI-04). 내 Slack 연결로만 읽고 보낸다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/slack/dms")
public class SlackDmController {

    private final SlackDmService slackDmService;

    @GetMapping
    public ApiResponse<List<Conversation>> getConversations(@LoginUser Long userId, @PathVariable Long workspaceId) {
        return ApiResponse.ok(slackDmService.getConversations(workspaceId, userId));
    }

    @GetMapping("/{channelId}/messages")
    public ApiResponse<List<Message>> getMessages(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                  @PathVariable String channelId) {
        return ApiResponse.ok(slackDmService.getMessages(workspaceId, userId, channelId));
    }

    @PostMapping("/{channelId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Message> send(@LoginUser Long userId, @PathVariable Long workspaceId,
                                     @PathVariable String channelId, @Valid @RequestBody SlackMessageRequest request) {
        return ApiResponse.ok(slackDmService.send(workspaceId, userId, channelId, request.text()));
    }
}
