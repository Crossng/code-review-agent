package com.repopilot.agent.worker;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.repopilot.modelcall.domain.ModelCallStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AgentWorkerModelCallRecordRequest(
        @JsonProperty("step_name")
        @NotBlank
        @Size(max = 120)
        String stepName,

        @JsonProperty("model_provider")
        @NotBlank
        @Size(max = 120)
        String modelProvider,

        @JsonProperty("model_name")
        @NotBlank
        @Size(max = 200)
        String modelName,

        @NotNull
        ModelCallStatus status,

        JsonNode prompt,

        JsonNode response,

        @JsonProperty("prompt_tokens")
        @PositiveOrZero
        Integer promptTokens,

        @JsonProperty("completion_tokens")
        @PositiveOrZero
        Integer completionTokens,

        @JsonProperty("total_tokens")
        @PositiveOrZero
        Integer totalTokens,

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
