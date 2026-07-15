package com.repopilot.agent.worker;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.toolcall.domain.ToolCallStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AgentWorkerToolCallRecordRequest(
        @JsonProperty("tool_name")
        @NotBlank
        @Size(max = 120)
        String toolName,

        @NotNull
        ToolCallStatus status,

        JsonNode input,

        JsonNode output,

        @JsonProperty("duration_ms")
        @PositiveOrZero
        Integer durationMs,

        @JsonProperty("error_message")
        @Size(max = 4000)
        String errorMessage,

        @JsonProperty("started_at")
        Instant startedAt,

        @JsonProperty("finished_at")
        Instant finishedAt
) {
}
