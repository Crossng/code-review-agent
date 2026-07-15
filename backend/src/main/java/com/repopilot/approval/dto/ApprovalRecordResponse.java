package com.repopilot.approval.dto;

import java.time.Instant;

import com.repopilot.approval.domain.ApprovalAction;
import com.repopilot.approval.domain.ApprovalRecord;
import com.repopilot.agent.domain.AgentTaskStatus;
import com.repopilot.patch.domain.PatchStatus;

public record ApprovalRecordResponse(
        Long id,
        Long agentTaskId,
        Long patchId,
        Long userId,
        ApprovalAction action,
        String comment,
        PatchStatus patchStatus,
        AgentTaskStatus taskStatus,
        Instant createdAt
) {

    public static ApprovalRecordResponse from(ApprovalRecord record) {
        return new ApprovalRecordResponse(
                record.getId(),
                record.getAgentTask().getId(),
                record.getPatch().getId(),
                record.getUser().getId(),
                record.getAction(),
                record.getComment(),
                record.getPatch().getStatus(),
                record.getAgentTask().getStatus(),
                record.getCreatedAt()
        );
    }
}

