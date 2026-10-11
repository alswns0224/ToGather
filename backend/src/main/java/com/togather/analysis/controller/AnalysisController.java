package com.togather.analysis.controller;

import com.togather.analysis.dto.AnalysisEvidenceDTO;
import com.togather.analysis.dto.AnalysisItemRequest;
import com.togather.analysis.dto.AnalysisItemResponse;
import com.togather.analysis.dto.AnalysisItemUpdateRequest;
import com.togather.analysis.dto.AnalysisResultDTO;
import com.togather.analysis.dto.AnalysisSummaryDTO;
import com.togather.analysis.dto.AnalyzeRequest;
import com.togather.analysis.dto.EvidenceLinkRequest;
import com.togather.analysis.service.AnalysisService;
import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
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

/** C-04-01 AnalysisController (UI-08 분석 결과 표시, 검토 및 확정) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    /** 스레드 즉시 분석. 새 메시지가 없으면 기존 분석을 돌려준다. (최대 30초) */
    @PostMapping("/analyses")
    public ApiResponse<AnalysisResultDTO> analyzeThread(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                        @Valid @RequestBody AnalyzeRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisService.analyzeThread(workspaceId, request.contentRefId(), userId));
    }

    @GetMapping("/analyses")
    public ApiResponse<List<AnalysisSummaryDTO>> getPendingAnalyses(@LoginUser Long userId,
                                                                    @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisService.getPendingAnalyses(workspaceId));
    }

    /** 스레드의 최신 분석 ID. 없으면 {"analysisId": null} */
    @GetMapping("/analyses/latest")
    public ApiResponse<Map<String, Long>> getLatestAnalysis(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                            @RequestParam Long contentRefId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        Map<String, Long> body = new java.util.HashMap<>();
        body.put("analysisId", analysisService.findLatestAnalysisId(workspaceId, contentRefId).orElse(null));
        return ApiResponse.ok(body);
    }

    @GetMapping("/analyses/{analysisId}")
    public ApiResponse<AnalysisResultDTO> getAnalysis(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                      @PathVariable Long analysisId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisService.getAnalysis(workspaceId, analysisId));
    }

    @PostMapping("/analyses/{analysisId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AnalysisItemResponse> addItem(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                     @PathVariable Long analysisId,
                                                     @Valid @RequestBody AnalysisItemRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisService.addItem(workspaceId, analysisId, request));
    }

    @PatchMapping("/analysis-items/{itemId}")
    public ApiResponse<AnalysisItemResponse> updateItem(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                        @PathVariable Long itemId,
                                                        @Valid @RequestBody AnalysisItemUpdateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisService.updateItem(workspaceId, itemId, request));
    }

    @DeleteMapping("/analysis-items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Long itemId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        analysisService.deleteItem(workspaceId, itemId);
    }

    @PostMapping("/analysis-items/{itemId}/evidences")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AnalysisEvidenceDTO> linkEvidence(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                         @PathVariable Long itemId,
                                                         @Valid @RequestBody EvidenceLinkRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(analysisService.linkEvidence(workspaceId, itemId, request.contentRefId()));
    }

    @DeleteMapping("/analysis-evidences/{evidenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkEvidence(@LoginUser Long userId, @PathVariable Long workspaceId,
                               @PathVariable Long evidenceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        analysisService.unlinkEvidence(workspaceId, evidenceId);
    }

    @PostMapping("/analyses/{analysisId}/confirm")
    public ApiResponse<AnalysisResultDTO> confirmAnalysis(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                          @PathVariable Long analysisId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        analysisService.confirmAnalysis(workspaceId, analysisId);
        return ApiResponse.ok(analysisService.getAnalysis(workspaceId, analysisId));
    }

    /** 확정 전 취소 (외부 서비스에 반영하지 않음) */
    @DeleteMapping("/analyses/{analysisId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discardAnalysis(@LoginUser Long userId, @PathVariable Long workspaceId,
                                @PathVariable Long analysisId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        analysisService.discardAnalysis(workspaceId, analysisId);
    }
}
