package com.repopilot.project.controller;

import java.util.List;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import com.repopilot.indexer.dto.CodeSearchResponse;
import com.repopilot.project.domain.ProjectStatus;
import com.repopilot.project.dto.CreateProjectRequest;
import com.repopilot.project.dto.ProjectControllerApiDocsResponse;
import com.repopilot.project.dto.ProjectControllerApiDocsSnapshotClearResponse;
import com.repopilot.project.dto.ProjectControllerApiDocsSnapshotResponse;
import com.repopilot.project.dto.ProjectControllerApiDocsSnapshotSummaryResponse;
import com.repopilot.project.dto.ProjectControllerApiListResponse;
import com.repopilot.project.dto.ProjectFileResponse;
import com.repopilot.project.dto.ProjectIndexResponse;
import com.repopilot.project.dto.ProjectResponse;
import com.repopilot.project.dto.ProjectSymbolResponse;
import com.repopilot.project.service.ProjectService;
import com.repopilot.repository.dto.CloneProjectResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ApiResponse<ProjectResponse> create(
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(ProjectResponse.from(projectService.create(request, principal.getId())));
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list(
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(required = false) String query,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.search(principal.getId(), status, query).stream().map(ProjectResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectResponse> get(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(ProjectResponse.from(projectService.get(id, principal.getId())));
    }

    @PostMapping("/{id}/clone")
    public ApiResponse<CloneProjectResponse> cloneRepository(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.cloneRepository(id, principal.getId()));
    }

    @PostMapping("/{id}/index")
    public ApiResponse<ProjectIndexResponse> index(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.index(id, principal.getId()));
    }

    @GetMapping("/{id}/symbols")
    public ApiResponse<List<ProjectSymbolResponse>> symbols(
            @PathVariable Long id,
            @RequestParam(required = false) String type,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.listSymbols(id, principal.getId(), type)
                .stream()
                .map(ProjectSymbolResponse::from)
                .toList());
    }

    @GetMapping("/{id}/search")
    public ApiResponse<CodeSearchResponse> search(
            @PathVariable Long id,
            @RequestParam String query,
            @RequestParam(defaultValue = "8") int limit,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.searchCode(id, principal.getId(), query, limit));
    }

    @GetMapping("/{id}/files")
    public ApiResponse<List<ProjectFileResponse>> files(
            @PathVariable Long id,
            @RequestParam(defaultValue = "6") int maxDepth,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.listFiles(id, principal.getId(), maxDepth));
    }

    @GetMapping("/{id}/controller-apis")
    public ApiResponse<ProjectControllerApiListResponse> controllerApis(
            @PathVariable Long id,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String riskCode,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.listControllerApis(id, principal.getId(), riskLevel, riskCode));
    }

    @GetMapping("/{id}/controller-apis/docs")
    public ApiResponse<ProjectControllerApiDocsResponse> controllerApiDocs(
            @PathVariable Long id,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String riskCode,
            @RequestParam(defaultValue = "12") int limit,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.controllerApiDocs(id, principal.getId(), riskLevel, riskCode, limit));
    }

    @GetMapping("/{id}/controller-apis/docs/snapshots")
    public ApiResponse<List<ProjectControllerApiDocsSnapshotSummaryResponse>> controllerApiDocSnapshots(
            @PathVariable Long id,
            @RequestParam(defaultValue = "5") int limit,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.listControllerApiDocSnapshots(id, principal.getId(), limit));
    }

    @PostMapping("/{id}/controller-apis/docs/snapshots")
    public ApiResponse<ProjectControllerApiDocsSnapshotResponse> createControllerApiDocSnapshot(
            @PathVariable Long id,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String riskCode,
            @RequestParam(defaultValue = "12") int limit,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.createControllerApiDocSnapshot(id, principal.getId(), riskLevel, riskCode, limit));
    }

    @DeleteMapping("/{id}/controller-apis/docs/snapshots")
    public ApiResponse<ProjectControllerApiDocsSnapshotClearResponse> clearControllerApiDocSnapshots(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.clearControllerApiDocSnapshots(id, principal.getId()));
    }

    @GetMapping("/{id}/controller-apis/docs/snapshots/{snapshotId}")
    public ApiResponse<ProjectControllerApiDocsSnapshotResponse> controllerApiDocSnapshot(
            @PathVariable Long id,
            @PathVariable Long snapshotId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(projectService.getControllerApiDocSnapshot(id, principal.getId(), snapshotId));
    }

    @DeleteMapping("/{id}/controller-apis/docs/snapshots/{snapshotId}")
    public ApiResponse<Void> deleteControllerApiDocSnapshot(
            @PathVariable Long id,
            @PathVariable Long snapshotId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        projectService.deleteControllerApiDocSnapshot(id, principal.getId(), snapshotId);
        return ApiResponse.ok(null);
    }
}
