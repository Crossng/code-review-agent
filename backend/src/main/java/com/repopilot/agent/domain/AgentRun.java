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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_run")
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_task_id")
    private AgentTask agentTask;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentRunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    protected AgentRun() {
    }

    public AgentRun(AgentTask agentTask) {
        this.agentTask = agentTask;
        this.status = AgentRunStatus.RUNNING;
    }

    @PrePersist
    void onCreate() {
        this.startedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public AgentTask getAgentTask() {
        return agentTask;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void markSuccess() {
        this.status = AgentRunStatus.SUCCESS;
        this.finishedAt = Instant.now();
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = AgentRunStatus.FAILED;
        this.finishedAt = Instant.now();
        this.errorMessage = errorMessage;
    }

    public void markCancelled(String message) {
        this.status = AgentRunStatus.CANCELLED;
        this.finishedAt = Instant.now();
        this.errorMessage = message;
    }
}
