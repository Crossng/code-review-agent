package com.repopilot.approval.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApprovalRequest(
        @NotNull Long patchId,
        @Size(max = 2000) String comment
) {
}

