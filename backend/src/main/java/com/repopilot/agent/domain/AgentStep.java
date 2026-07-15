package com.repopilot.agent.domain;

import java.time.Instant;

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
@Table(name = "agent_step")
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id")
    private AgentRun agentRun;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentStepStatus status;

    @Column(name = "input_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String inputJson;

    @Column(name = "output_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String outputJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AgentStep() {
    }

    public AgentStep(AgentRun agentRun, String stepName, AgentStepStatus status, String inputJson, String outputJson) {
        this(agentRun, stepName, status, inputJson, outputJson, null);
    }

    public AgentStep(
            AgentRun agentRun,
            String stepName,
            AgentStepStatus status,
            String inputJson,
            String outputJson,
            String errorMessage
    ) {
        this.agentRun = agentRun;
        this.stepName = stepName;
        this.status = status;
        this.inputJson = inputJson;
        this.outputJson = outputJson;
        this.errorMessage = errorMessage;
        this.startedAt = Instant.now();
        if (status == AgentStepStatus.SUCCESS || status == AgentStepStatus.FAILED) {
            this.finishedAt = Instant.now();
        }
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

    public AgentStepStatus getStatus() {
        return status;
    }

    public String getInputJson() {
        return inputJson;
    }

    public String getOutputJson() {
        return outputJson;
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
