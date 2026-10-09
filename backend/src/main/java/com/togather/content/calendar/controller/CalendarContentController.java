package com.togather.content.calendar.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.response.ApiResponse;
import com.togather.content.calendar.dto.CalendarEventCreateRequest;
import com.togather.content.calendar.dto.CalendarEventResponse;
import com.togather.content.calendar.dto.CalendarEventUpdateRequest;
import com.togather.content.calendar.service.CalendarContentService;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

/** C-03 CalendarContentController (UI-07) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/calendar/events")
public class CalendarContentController {

    private final CalendarContentService calendarContentService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    /** 예: ?from=2026-10-01T00:00:00&to=2026-11-01T00:00:00 */
    @GetMapping
    public ApiResponse<List<CalendarEventResponse>> getEvents(
            @LoginUser Long userId, @PathVariable Long workspaceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(calendarContentService.getEvents(workspaceId, userId, from, to));
    }

    @GetMapping("/{contentRefId}")
    public ApiResponse<CalendarEventResponse> getEvent(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                       @PathVariable Long contentRefId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(calendarContentService.getEvent(workspaceId, userId, contentRefId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CalendarEventResponse> createEvent(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                          @Valid @RequestBody CalendarEventCreateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(calendarContentService.createEvent(workspaceId, userId, request));
    }

    @PatchMapping("/{contentRefId}")
    public ApiResponse<CalendarEventResponse> updateEvent(@LoginUser Long userId, @PathVariable Long workspaceId,
                                                          @PathVariable Long contentRefId,
                                                          @Valid @RequestBody CalendarEventUpdateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(calendarContentService.updateEvent(workspaceId, userId, contentRefId, request));
    }

    @DeleteMapping("/{contentRefId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvent(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Long contentRefId) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        calendarContentService.deleteEvent(workspaceId, userId, contentRefId);
    }
}
