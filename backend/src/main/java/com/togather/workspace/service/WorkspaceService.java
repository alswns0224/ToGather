package com.togather.workspace.service;

import com.togather.common.auth.WorkspaceAccessChecker;
import com.togather.common.enums.WorkspaceRole;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.workspace.domain.Workspace;
import com.togather.workspace.domain.WorkspaceMember;
import com.togather.workspace.dto.WorkspaceRequest;
import com.togather.workspace.dto.WorkspaceResponse;
import com.togather.workspace.repository.WorkspaceMemberRepository;
import com.togather.workspace.repository.WorkspaceRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-01-07. 워크스페이스 CRUD (UC-02-01 ~ 04)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    /** 사용자가 참여한 워크스페이스 목록 (최근 생성순) */
    public List<WorkspaceResponse> getWorkspaces(Long userId) {
        Map<Long, WorkspaceRole> roles = workspaceMemberRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(WorkspaceMember::getWorkspaceId, WorkspaceMember::getRole));
        if (roles.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : workspaceMemberRepository.countByWorkspaceIds(roles.keySet())) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return workspaceRepository.findAllById(roles.keySet()).stream()
                .sorted(Comparator.comparing(Workspace::getCreatedAt).reversed())
                .map(ws -> WorkspaceResponse.of(ws, counts.getOrDefault(ws.getWorkspaceId(), 0L),
                        roles.get(ws.getWorkspaceId())))
                .toList();
    }

    public WorkspaceResponse getWorkspace(Long workspaceId, Long userId) {
        WorkspaceMember member = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));
        Workspace workspace = findWorkspace(workspaceId);
        return WorkspaceResponse.of(workspace, workspaceMemberRepository.countByWorkspaceId(workspaceId), member.getRole());
    }

    /** 워크스페이스를 저장하고 생성자를 OWNER로 등록한다. */
    @Transactional
    public WorkspaceResponse createWorkspace(Long userId, WorkspaceRequest request) {
        Workspace workspace = workspaceRepository.save(new Workspace(request.name().strip(), normalize(request.description())));
        workspaceMemberRepository.save(new WorkspaceMember(workspace.getWorkspaceId(), userId, WorkspaceRole.OWNER));
        return WorkspaceResponse.of(workspace, 1, WorkspaceRole.OWNER);
    }

    @Transactional
    public WorkspaceResponse updateWorkspace(Long workspaceId, Long userId, WorkspaceRequest request) {
        workspaceAccessChecker.checkOwner(workspaceId, userId);
        Workspace workspace = findWorkspace(workspaceId);
        workspace.update(request.name().strip(), normalize(request.description()));
        return WorkspaceResponse.of(workspace, workspaceMemberRepository.countByWorkspaceId(workspaceId), WorkspaceRole.OWNER);
    }

    /** 워크스페이스와 소속 데이터(팀원, Task, 회의, 연동 등)를 삭제한다. 하위 데이터는 DB의 ON DELETE CASCADE로 지워진다. */
    @Transactional
    public void deleteWorkspace(Long workspaceId, Long userId) {
        workspaceAccessChecker.checkOwner(workspaceId, userId);
        workspaceRepository.delete(findWorkspace(workspaceId));
    }

    /** 다른 Service에서 워크스페이스 이름 등을 표시할 때 사용 */
    public Map<Long, Workspace> getWorkspacesByIds(List<Long> workspaceIds) {
        return workspaceRepository.findAllById(workspaceIds).stream()
                .collect(Collectors.toMap(Workspace::getWorkspaceId, Function.identity()));
    }

    Workspace findWorkspace(Long workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    private static String normalize(String description) {
        return description == null || description.isBlank() ? null : description.strip();
    }
}
