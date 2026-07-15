package com.repopilot.approval.controller;

import java.util.List;

import com.repopilot.approval.dto.ApprovalRecordResponse;
import com.repopilot.approval.dto.ApprovalRequest;
import com.repopilot.approval.service.ApprovalService;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/tasks/{taskId}/approval")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/approve")
    public ApiResponse<ApprovalRecordResponse> approve(
            @PathVariable @Positive Long taskId,
            @Valid @RequestBody ApprovalRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(ApprovalRecordResponse.from(
                approvalService.approve(taskId, request.patchId(), principal.getId(), request.comment())
        ));
    }

    @PostMapping("/reject")
    public ApiResponse<ApprovalRecordResponse> reject(
            @PathVariable @Positive Long taskId,
            @Valid @RequestBody ApprovalRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(ApprovalRecordResponse.from(
                approvalService.reject(taskId, request.patchId(), principal.getId(), request.comment())
        ));
    }

    @GetMapping
    public ApiResponse<List<ApprovalRecordResponse>> list(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(approvalService.list(taskId, principal.getId())
                .stream()
                .map(ApprovalRecordResponse::from)
                .toList());
    }
}

