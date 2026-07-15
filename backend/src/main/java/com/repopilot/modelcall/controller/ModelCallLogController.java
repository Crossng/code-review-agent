package com.repopilot.modelcall.controller;

import java.util.List;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import com.repopilot.modelcall.dto.ModelCallLogResponse;
import com.repopilot.modelcall.service.ModelCallQueryService;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/agent/runs/{runId}/model-calls")
public class ModelCallLogController {

    private final ModelCallQueryService modelCallQueryService;

    public ModelCallLogController(ModelCallQueryService modelCallQueryService) {
        this.modelCallQueryService = modelCallQueryService;
    }

    @GetMapping
    public ApiResponse<List<ModelCallLogResponse>> list(
            @PathVariable @Positive Long runId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(modelCallQueryService.listByRun(runId, principal.getId())
                .stream()
                .map(ModelCallLogResponse::from)
                .toList());
    }
}
