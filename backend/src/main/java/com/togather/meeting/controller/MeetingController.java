package com.togather.meeting.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.response.ApiResponse;
import com.togather.meeting.dto.AgendaRequest;
import com.togather.meeting.dto.AvailabilityResponse;
import com.togather.meeting.dto.AvailabilitySaveRequest;
import com.togather.meeting.dto.ConfirmRequest;
import com.togather.meeting.dto.MeetingCreateRequest;
import com.togather.meeting.dto.MeetingDetailResponse;
import com.togather.meeting.dto.MeetingSummaryResponse;
import com.togather.meeting.dto.MeetingUpdateRequest;
import com.togather.meeting.dto.ResponseStatusResponse;
import com.togather.meeting.dto.TimeCandidateResponse;
import com.togather.meeting.service.MeetingService;
import com.togather.task.dto.TaskSummary;
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

/** C-06 MeetingController (UI-10) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/meetings")
public class MeetingController {

    private final MeetingService meetingService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    @GetMapping
    public ApiResponse<List<MeetingSummaryResponse>> getMeetings(@LoginUser Long userId, @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.getMeetings(workspaceId, userId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MeetingDetailResponse> createMeeting(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                            @Valid @RequestBody MeetingCreateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.createMeeting(workspaceId, userId, request));
    }

    /** 안건으로 고를 수 있는 진행 중 Task */
    @GetMapping("/agenda-candidates")
    public ApiResponse<List<TaskSummary>> getAgendaCandidates(@LoginUser Long userId, @PathVariable Long workspaceId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.getAgendaCandidates(workspaceId));
    }

    @GetMapping("/{meetingId}")
    public ApiResponse<MeetingDetailResponse> getMeeting(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                         @PathVariable Long meetingId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.getMeeting(workspaceId, meetingId, userId));
    }

    @PatchMapping("/{meetingId}")
    public ApiResponse<MeetingDetailResponse> updateMeeting(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                            @PathVariable Long meetingId,
                                                            @Valid @RequestBody MeetingUpdateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.updateMeeting(workspaceId, meetingId, userId, request));
    }

    @PostMapping("/{meetingId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelMeeting(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Long meetingId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        meetingService.cancelMeeting(workspaceId, meetingId, userId);
    }

    // ===== 안건 =====

    @PostMapping("/{meetingId}/agendas")
    public ApiResponse<MeetingDetailResponse> addAgenda(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                        @PathVariable Long meetingId,
                                                        @Valid @RequestBody AgendaRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.addAgenda(workspaceId, meetingId, userId, request.taskId()));
    }

    @PatchMapping("/{meetingId}/agendas/{agendaId}")
    public ApiResponse<MeetingDetailResponse> changeAgenda(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                           @PathVariable Long meetingId, @PathVariable Long agendaId,
                                                           @Valid @RequestBody AgendaRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.changeAgenda(workspaceId, meetingId, agendaId, userId, request.taskId()));
    }

    @DeleteMapping("/{meetingId}/agendas/{agendaId}")
    public ApiResponse<MeetingDetailResponse> deleteAgenda(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                           @PathVariable Long meetingId, @PathVariable Long agendaId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.deleteAgenda(workspaceId, meetingId, agendaId, userId));
    }

    // ===== 가용 시간 =====

    @GetMapping("/{meetingId}/availability/me")
    public ApiResponse<AvailabilityResponse> getMyAvailability(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                               @PathVariable Long meetingId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.getMyAvailability(workspaceId, meetingId, userId));
    }

    /** 내 가용 시간을 통째로 바꾼다. */
    @PostMapping("/{meetingId}/availability/me")
    public ApiResponse<AvailabilityResponse> saveAvailability(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                              @PathVariable Long meetingId,
                                                              @Valid @RequestBody AvailabilitySaveRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.saveAvailability(workspaceId, meetingId, userId, request));
    }

    @GetMapping("/{meetingId}/responses")
    public ApiResponse<ResponseStatusResponse> getResponseStatus(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                                 @PathVariable Long meetingId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.getResponseStatus(workspaceId, meetingId));
    }

    // ===== 추천 · 확정 =====

    @PostMapping("/{meetingId}/recommendations")
    public ApiResponse<List<TimeCandidateResponse>> recommendTimes(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                                   @PathVariable Long meetingId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.recommendTimes(workspaceId, meetingId, userId));
    }

    @PostMapping("/{meetingId}/confirm")
    public ApiResponse<MeetingDetailResponse> confirmMeeting(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                             @PathVariable Long meetingId,
                                                             @Valid @RequestBody ConfirmRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.confirmMeeting(workspaceId, meetingId, userId, request.startAt()));
    }

    @PostMapping("/{meetingId}/calendar-retry")
    public ApiResponse<MeetingDetailResponse> retryCalendarRegistration(@LoginUser Long userId,
                                                                        @PathVariable Long workspaceId,
                                                                        @PathVariable Long meetingId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(meetingService.retryCalendarRegistration(workspaceId, meetingId, userId));
    }
}
