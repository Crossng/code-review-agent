package com.repopilot.agent.worker;

import com.repopilot.agent.dto.AgentStepResponse;
import com.repopilot.common.ApiResponse;
import com.repopilot.modelcall.dto.ModelCallLogResponse;
import com.repopilot.patch.dto.PatchRecordResponse;
import com.repopilot.toolcall.dto.ToolCallLogResponse;
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

    @PostMapping("/api/internal/agent-worker/runs/{runId}/tool-calls")
    public ApiResponse<ToolCallLogResponse> recordToolCall(
            @PathVariable Long runId,
            @RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @Valid @RequestBody AgentWorkerToolCallRecordRequest request
    ) {
        return ApiResponse.ok(callbackService.recordToolCall(runId, callbackToken, request));
    }

    @PostMapping("/api/internal/agent-worker/runs/{runId}/model-calls")
    public ApiResponse<ModelCallLogResponse> recordModelCall(
            @PathVariable Long runId,
            @RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @Valid @RequestBody AgentWorkerModelCallRecordRequest request
    ) {
        return ApiResponse.ok(callbackService.recordModelCall(runId, callbackToken, request));
    }

    @PostMapping("/api/internal/agent-worker/runs/{runId}/patches")
    public ApiResponse<PatchRecordResponse> recordPatch(
            @PathVariable Long runId,
            @RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @Valid @RequestBody AgentWorkerPatchRecordRequest request
    ) {
        return ApiResponse.ok(callbackService.recordPatch(runId, callbackToken, request));
    }

    @PostMapping("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/safety")
    public ApiResponse<AgentWorkerPatchSafetyResponse> validatePatchSafety(
            @PathVariable Long runId,
            @PathVariable Long patchId,
            @RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String callbackToken
    ) {
        return ApiResponse.ok(callbackService.validatePatchSafety(runId, patchId, callbackToken));
    }

    @PostMapping("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/sandbox-tests")
    public ApiResponse<AgentWorkerPatchSandboxResponse> runPatchSandboxTests(
            @PathVariable Long runId,
            @PathVariable Long patchId,
            @RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String callbackToken
    ) {
        return ApiResponse.ok(callbackService.runPatchSandboxTests(runId, patchId, callbackToken));
    }

    @PostMapping("/api/internal/agent-worker/runs/{runId}/patches/{patchId}/review")
    public ApiResponse<AgentWorkerPatchReviewResponse> reviewPatch(
            @PathVariable Long runId,
            @PathVariable Long patchId,
            @RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String callbackToken
    ) {
        return ApiResponse.ok(callbackService.reviewPatch(runId, patchId, callbackToken));
    }

    @PostMapping("/api/internal/agent-worker/runs/{runId}/status")
    public ApiResponse<AgentWorkerRunStatusUpdateResponse> updateStatus(
            @PathVariable Long runId,
            @RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String callbackToken,
            @Valid @RequestBody AgentWorkerRunStatusUpdateRequest request
    ) {
        return ApiResponse.ok(callbackService.updateStatus(runId, callbackToken, request));
    }
}
