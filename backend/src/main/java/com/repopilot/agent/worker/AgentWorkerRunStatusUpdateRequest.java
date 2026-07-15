package com.repopilot.agent.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.repopilot.agent.domain.AgentRunStatus;
import com.repopilot.agent.domain.AgentTaskStatus;
import jakarta.validation.constraints.Size;

public record AgentWorkerRunStatusUpdateRequest(
        @JsonProperty("task_status")
        AgentTaskStatus taskStatus,

        @JsonProperty("run_status")
        AgentRunStatus runStatus,

        @JsonProperty("error_message")
        @Size(max = 4000)
        String errorMessage,

        @JsonProperty("stream_message")
        @Size(max = 4000)
        String streamMessage,

        @JsonProperty("complete_stream")
        boolean completeStream
) {
}
