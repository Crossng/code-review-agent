package com.repopilot.agent.worker;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentTask;
import com.repopilot.agent.domain.AgentTaskStatus;

public record AgentWorkerRunStatusUpdateResponse(
        Long taskId,
        AgentTaskStatus taskStatus,
        Long runId,
        AgentRunStatus runStatus,
        boolean streamCompleted
) {

    static AgentWorkerRunStatusUpdateResponse from(AgentTask task, AgentRun run, boolean streamCompleted) {
        return new AgentWorkerRunStatusUpdateResponse(
                task.getId(),
                task.getStatus(),
                run.getId(),
                run.getStatus(),
                streamCompleted
        );
    }
}
