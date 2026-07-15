package com.repopilot.toolcall.controller;

import java.util.List;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import com.repopilot.toolcall.dto.ToolCallLogResponse;
import com.repopilot.toolcall.service.ToolCallQueryService;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/agent/runs/{runId}/tool-calls")
public class ToolCallLogController {

    private final ToolCallQueryService toolCallQueryService;

    public ToolCallLogController(ToolCallQueryService toolCallQueryService) {
        this.toolCallQueryService = toolCallQueryService;
    }

    @GetMapping
    public ApiResponse<List<ToolCallLogResponse>> list(
            @PathVariable @Positive Long runId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(toolCallQueryService.listByRun(runId, principal.getId())
                .stream()
                .map(ToolCallLogResponse::from)
                .toList());
    }
}
