package com.togather.task.controller;

import com.togather.common.auth.LoginUser;
import com.togather.common.enums.Provider;
import com.togather.common.response.ApiResponse;
import com.togather.task.dto.TaskReferenceCandidate;
import com.togather.task.service.TaskResourceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/tasks/{taskId}")
public class TaskResourceController {

    private final TaskResourceService resources;

    @GetMapping("/references/candidates")
    public ApiResponse<List<TaskReferenceCandidate>> getCandidates(
        @LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Long taskId,
        @RequestParam Provider provider, @RequestParam(required = false) String query
    ) {
        return ApiResponse.ok(resources.getCandidates(workspaceId, userId, taskId, provider, query));
    }

    @PutMapping("/references/{contentRefId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void link(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Long taskId,
                     @PathVariable Long contentRefId) {
        resources.link(workspaceId, userId, taskId, contentRefId);
    }

    @DeleteMapping("/references/{contentRefId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Long taskId,
                       @PathVariable Long contentRefId) {
        resources.unlink(workspaceId, userId, taskId, contentRefId);
    }

    @PutMapping("/deliverables/{deliverableId}/submission")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Long taskId,
                       @PathVariable Long deliverableId, @Valid @RequestBody Submission submission) {
        resources.submit(workspaceId, userId, taskId, deliverableId, submission.contentRefId());
    }

    @DeleteMapping("/deliverables/{deliverableId}/submission")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubmit(@LoginUser Long userId, @PathVariable Long workspaceId, @PathVariable Long taskId,
                         @PathVariable Long deliverableId) {
        resources.unsubmit(workspaceId, userId, taskId, deliverableId);
    }

    public record Submission(@NotNull @Positive Long contentRefId) {
    }
}
