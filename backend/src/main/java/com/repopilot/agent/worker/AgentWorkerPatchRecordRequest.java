package com.repopilot.agent.worker;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentWorkerPatchRecordRequest(
        @JsonProperty("base_branch")
        @Size(max = 200)
        String baseBranch,

        @JsonProperty("target_branch")
        @Size(max = 200)
        String targetBranch,

        @JsonProperty("diff_content")
        @NotBlank
        String diffContent,

        @Size(max = 4000)
        String summary,

        @JsonProperty("generation_mode")
        @NotBlank
        @Size(max = 120)
        String generationMode,

        @JsonProperty("generation_provider")
        @NotBlank
        @Size(max = 120)
        String generationProvider,

        @JsonProperty("generation_model")
        @Size(max = 200)
        String generationModel
) {
}
