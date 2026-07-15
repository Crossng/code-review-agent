package com.repopilot.patch.dto;

import java.time.Instant;
import java.util.List;

import com.repopilot.patch.domain.PatchRecord;
import com.repopilot.patch.domain.PatchStatus;

public record PatchRecordResponse(
        Long id,
        Long agentTaskId,
        Long agentRunId,
        String baseBranch,
        String targetBranch,
        String diffContent,
        String summary,
        String generationMode,
        List<PatchChangedFileResponse> changedFiles,
        PatchStatus status,
        Instant createdAt
) {

    public static PatchRecordResponse from(PatchRecord patch) {
        return new PatchRecordResponse(
                patch.getId(),
                patch.getAgentTask().getId(),
                patch.getAgentRun().getId(),
                patch.getBaseBranch(),
                patch.getTargetBranch(),
                patch.getDiffContent(),
                patch.getSummary(),
                patch.getGenerationMode(),
                PatchChangedFileResponse.fromDiff(patch.getDiffContent()),
                patch.getStatus(),
                patch.getCreatedAt()
        );
    }
}
