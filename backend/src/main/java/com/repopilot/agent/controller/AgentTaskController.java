package com.repopilot.agent.controller;

import java.util.List;

import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.agent.domain.AgentTaskType;
import com.repopilot.agent.dto.AgentRunReportResponse;
import com.repopilot.agent.dto.AgentRunReportSnapshotResponse;
import com.repopilot.agent.dto.AgentRunReportSnapshotSummaryResponse;
import com.repopilot.agent.dto.AgentRunResponse;
import com.repopilot.agent.dto.AgentStepResponse;
import com.repopilot.agent.dto.AgentTaskResponse;
import com.repopilot.agent.dto.CreateAgentTaskRequest;
import com.repopilot.agent.service.AgentTaskService;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/tasks")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;

    public AgentTaskController(AgentTaskService agentTaskService) {
        this.agentTaskService = agentTaskService;
    }

    @PostMapping
    public ApiResponse<AgentTaskResponse> create(
            @Valid @RequestBody CreateAgentTaskRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(AgentTaskResponse.from(agentTaskService.create(request, principal.getId())));
    }

    @GetMapping
    public ApiResponse<List<AgentTaskResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) AgentTaskStatus status,
            @RequestParam(required = false) AgentTaskType taskType,
            @RequestParam(required = false) String query
    ) {
        return ApiResponse.ok(agentTaskService.search(principal.getId(), projectId, status, taskType, query)
                .stream()
                .map(AgentTaskResponse::from)
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentTaskResponse> get(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(AgentTaskResponse.from(agentTaskService.get(id, principal.getId())));
    }

    @PostMapping("/{id}/run")
    public ApiResponse<AgentRunResponse> run(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(AgentRunResponse.from(agentTaskService.run(id, principal.getId())));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AgentTaskResponse> cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(AgentTaskResponse.from(agentTaskService.cancel(id, principal.getId())));
    }

    @GetMapping("/{id}/steps")
    public ApiResponse<List<AgentStepResponse>> steps(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(agentTaskService.steps(id, principal.getId()).stream().map(AgentStepResponse::from).toList());
    }

    @GetMapping("/{id}/run-report")
    public ApiResponse<AgentRunReportResponse> runReport(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(agentTaskService.runReport(id, principal.getId()));
    }

    @GetMapping("/{id}/run-report/snapshots")
    public ApiResponse<List<AgentRunReportSnapshotSummaryResponse>> runReportSnapshots(
            @PathVariable Long id,
            @RequestParam(defaultValue = "5") int limit,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(agentTaskService.listRunReportSnapshots(id, principal.getId(), limit));
    }

    @PostMapping("/{id}/run-report/snapshots")
    public ApiResponse<AgentRunReportSnapshotResponse> createRunReportSnapshot(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(agentTaskService.createRunReportSnapshot(id, principal.getId()));
    }

    @GetMapping("/{id}/run-report/snapshots/{snapshotId}")
    public ApiResponse<AgentRunReportSnapshotResponse> runReportSnapshot(
            @PathVariable Long id,
            @PathVariable Long snapshotId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(agentTaskService.getRunReportSnapshot(id, principal.getId(), snapshotId));
    }
}
