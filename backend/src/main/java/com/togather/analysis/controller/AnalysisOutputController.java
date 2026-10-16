package com.togather.analysis.controller;

import com.togather.analysis.dto.AnalysisItemResponse;
import com.togather.analysis.dto.DocumentDraftDTO;
import com.togather.analysis.dto.FollowUpTaskRequest;
import com.togather.analysis.dto.MeetingDraftDTO;
import com.togather.analysis.dto.NotionOutputRequest;
import com.togather.analysis.dto.OutputResultResponse;
import com.togather.analysis.service.AnalysisOutputService;
import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.response.ApiResponse;
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

/**
 * 확정된 분석의 실행 연계 (UI-08 처리 결과). 실패한 단계는 같은 요청을 다시 보내 재시도한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}")
public class AnalysisOutputController {

    private final AnalysisOutputService analysisOutputService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    @GetMapping("/analyses/{analysisId}/notion-draft")
    public ApiResponse<DocumentDraftDTO> previewNotionDocument(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                               @PathVariable Long analysisId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisOutputService.previewNotionDocument(workspaceId, analysisId));
    }

    @PostMapping("/analyses/{analysisId}/notion")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OutputResultResponse> createNotionDocument(@LoginUser Long userId,
                                                                  @PathVariable Long workspaceId,
                                                                  @PathVariable Long analysisId,
                                                                  @Valid @RequestBody NotionOutputRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisOutputService.createNotionDocument(workspaceId, userId, analysisId, request));
    }

    @GetMapping("/analyses/{analysisId}/follow-ups")
    public ApiResponse<List<AnalysisItemResponse>> getFollowUpItems(@LoginUser Long userId,
                                                                    @PathVariable Long workspaceId,
                                                                    @PathVariable Long analysisId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisOutputService.getFollowUpItems(workspaceId, analysisId));
    }

    @PostMapping("/analysis-items/{itemId}/task")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OutputResultResponse> createFollowUpTask(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                                @PathVariable Long itemId,
                                                                @Valid @RequestBody FollowUpTaskRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisOutputService.createFollowUpTask(workspaceId, itemId, request));
    }

    @GetMapping("/analysis-items/{itemId}/meeting-draft")
    public ApiResponse<MeetingDraftDTO> previewMeetingSchedule(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                               @PathVariable Long itemId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisOutputService.previewMeetingSchedule(workspaceId, userId, itemId));
    }

    @PostMapping("/analysis-items/{itemId}/meeting")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OutputResultResponse> registerMeetingSchedule(@LoginUser Long userId,
                                                                     @PathVariable Long workspaceId,
                                                                     @PathVariable Long itemId,
                                                                     @Valid @RequestBody MeetingDraftDTO draft) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisOutputService.registerMeetingSchedule(workspaceId, userId, itemId, draft));
    }
}
