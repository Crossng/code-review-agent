package com.repopilot.agent.worker;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AgentWorkerStartResult(
        @JsonProperty("run_id") Long runId,
        boolean accepted,
        String status,
        @JsonProperty("graph_nodes") List<String> graphNodes
) {
}
