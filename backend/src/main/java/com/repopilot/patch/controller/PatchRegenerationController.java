package com.repopilot.patch.controller;

import com.repopilot.agent.service.AgentTaskService;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import com.repopilot.patch.dto.PatchRecordResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/tasks/{taskId}")
public class PatchRegenerationController {

    private final AgentTaskService agentTaskService;

    public PatchRegenerationController(AgentTaskService agentTaskService) {
        this.agentTaskService = agentTaskService;
    }

    @PostMapping({"/patches/regenerate", "/patch/regenerate"})
    public ApiResponse<PatchRecordResponse> regenerate(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(PatchRecordResponse.from(agentTaskService.regeneratePatch(taskId, principal.getId())));
    }
}
