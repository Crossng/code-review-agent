package com.repopilot.agent.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.agent.domain.AgentStepStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentWorkerStepRecordRequest(
        @JsonProperty("step_name")
        @NotBlank
        @Size(max = 120)
        String stepName,

        @NotNull
        AgentStepStatus status,

        JsonNode input,

        JsonNode output,

        @JsonProperty("error_message")
        @Size(max = 4000)
        String errorMessage
) {
}
