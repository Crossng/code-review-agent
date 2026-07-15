package com.repopilot.agent.worker;

import com.repopilot.agent.dto.AgentStepResponse;
import com.repopilot.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentWorkerCallbackController {

    public static final String CALLBACK_TOKEN_HEADER = "X-RepoPilot-Worker-Token";

    private final AgentWorkerCallbackService callbackService;

    public AgentWorkerCallbackController(AgentWorkerCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/api/internal/agent-worker/runs/{runId}/steps")
    public ApiResponse<AgentStepResponse> recordStep(
            @PathVariable Long runId,
            @RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @Valid @RequestBody AgentWorkerStepRecordRequest request
    ) {
        return ApiResponse.ok(callbackService.recordStep(runId, callbackToken, request));
    }
}
