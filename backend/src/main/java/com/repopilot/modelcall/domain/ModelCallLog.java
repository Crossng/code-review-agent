package com.repopilot.modelcall.domain;

import java.time.Instant;

import com.repopilot.agent.domain.AgentRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "model_call_log")
public class ModelCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id")
    private AgentRun agentRun;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(name = "model_provider", nullable = false)
    private String modelProvider;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "prompt_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String promptJson;

    @Column(name = "response_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String responseJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModelCallStatus status;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    protected ModelCallLog() {
    }

    public ModelCallLog(
            AgentRun agentRun,
            String stepName,
            String modelProvider,
            String modelName,
            String promptJson,
            String responseJson,
            ModelCallStatus status,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Integer durationMs,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt
    ) {
        this.agentRun = agentRun;
        this.stepName = stepName;
        this.modelProvider = modelProvider;
        this.modelName = modelName;
        this.promptJson = promptJson;
        this.responseJson = responseJson;
        this.status = status;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    public Long getId() {
        return id;
    }

    public AgentRun getAgentRun() {
        return agentRun;
    }

    public String getStepName() {
        return stepName;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getPromptJson() {
        return promptJson;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public ModelCallStatus getStatus() {
        return status;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
