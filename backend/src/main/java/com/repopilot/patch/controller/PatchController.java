package com.repopilot.patch.controller;

import java.util.List;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import com.repopilot.patch.dto.PatchRecordResponse;
import com.repopilot.patch.service.PatchQueryService;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/tasks/{taskId}/patches")
public class PatchController {

    private final PatchQueryService patchQueryService;

    public PatchController(PatchQueryService patchQueryService) {
        this.patchQueryService = patchQueryService;
    }

    @GetMapping
    public ApiResponse<List<PatchRecordResponse>> list(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(patchQueryService.listByTask(taskId, principal.getId())
                .stream()
                .map(PatchRecordResponse::from)
                .toList());
    }

    @GetMapping("/latest")
    public ApiResponse<PatchRecordResponse> latest(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(PatchRecordResponse.from(patchQueryService.latest(taskId, principal.getId())));
    }
}
