package com.togather.assistant.controller;

import com.togather.assistant.dto.DetectResultResponse;
import com.togather.assistant.dto.MyTodoListDTO;
import com.togather.assistant.dto.PersonalTaskRequest;
import com.togather.assistant.dto.PersonalTaskResponse;
import com.togather.assistant.dto.SuggestionDTO;
import com.togather.assistant.dto.SuggestionUpdateRequest;
import com.togather.assistant.service.SuggestionService;
import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.SuggestionType;
import com.togather.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-08-01 SuggestionController (UI-13). 후보는 개인 데이터라 로그인 사용자 본인 것만 다룬다.
 * Slack·Calendar 연결이 워크스페이스 단위라서 워크스페이스 경로 아래에 둔다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/assistant")
public class SuggestionController {

    private final SuggestionService suggestionService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    /** @param type SCHEDULE 또는 TASK. 비우면 전체 */
    @GetMapping("/suggestions")
    public ApiResponse<List<SuggestionDTO>> getSuggestions(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                           @RequestParam(required = false) SuggestionType type) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(suggestionService.getSuggestions(workspaceId, userId, type));
    }

    @GetMapping("/todos")
    public ApiResponse<MyTodoListDTO> getMyTodoList(@LoginUser Long userId, @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(suggestionService.getMyTodoList(workspaceId, userId));
    }

    /** 새 메시지에서 후보를 찾는다. (Gemini 호출) */
    @PostMapping("/suggestions/detect")
    public ApiResponse<DetectResultResponse> detectCandidates(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                              @RequestParam(required = false) SuggestionType type) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(suggestionService.detectCandidates(workspaceId, userId, type));
    }

    @PatchMapping("/suggestions/{suggestionId}")
    public ApiResponse<SuggestionDTO> updateSuggestion(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                       @PathVariable Long suggestionId,
                                                       @Valid @RequestBody SuggestionUpdateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(suggestionService.updateSuggestion(workspaceId, userId, suggestionId, request));
    }

    /** 일정 승인 → 개인 캘린더 등록. 결과: 일정의 contentRefId */
    @PostMapping("/suggestions/{suggestionId}/approve-schedule")
    public ApiResponse<Map<String, Long>> approveSchedule(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                          @PathVariable Long suggestionId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(Map.of("contentRefId",
                suggestionService.approveSchedule(workspaceId, userId, suggestionId)));
    }

    /** 할 일 승인 → 개인 할 일 등록 */
    @PostMapping("/suggestions/{suggestionId}/approve-task")
    public ApiResponse<Map<String, Long>> approveTask(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                      @PathVariable Long suggestionId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(Map.of("personalTaskId",
                suggestionService.approveTask(workspaceId, userId, suggestionId)));
    }

    @PostMapping("/suggestions/{suggestionId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissSuggestion(@LoginUser Long userId, @PathVariable Long workspaceId,
                                  @PathVariable Long suggestionId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        suggestionService.dismissSuggestion(workspaceId, userId, suggestionId);
    }

    @PostMapping("/personal-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PersonalTaskResponse> createPersonalTask(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                                @Valid @RequestBody PersonalTaskRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(suggestionService.createPersonalTask(userId, request.title(), request.dueDate()));
    }

    @PatchMapping("/personal-tasks/{personalTaskId}")
    public ApiResponse<PersonalTaskResponse> updatePersonalTask(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                                @PathVariable Long personalTaskId,
                                                                @Valid @RequestBody PersonalTaskRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(suggestionService.updatePersonalTask(userId, personalTaskId, request.title(),
                request.dueDate()));
    }

    @DeleteMapping("/personal-tasks/{personalTaskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePersonalTask(@LoginUser Long userId, @PathVariable Long workspaceId,
                                   @PathVariable Long personalTaskId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        suggestionService.deletePersonalTask(userId, personalTaskId);
    }

    @PostMapping("/personal-tasks/{personalTaskId}/reopen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reopenPersonalTask(@LoginUser Long userId, @PathVariable Long workspaceId,
                                   @PathVariable Long personalTaskId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        suggestionService.reopenPersonalTask(userId, personalTaskId);
    }

    @PostMapping("/personal-tasks/{personalTaskId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completePersonalTask(@LoginUser Long userId, @PathVariable Long workspaceId,
                                     @PathVariable Long personalTaskId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        suggestionService.completePersonalTask(userId, personalTaskId);
    }
}
