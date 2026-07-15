package com.repopilot.sandbox.domain;

import java.time.Instant;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.patch.domain.PatchRecord;
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
@Table(name = "test_run")
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id")
    private AgentRun agentRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patch_id")
    private PatchRecord patch;

    @Column(nullable = false, columnDefinition = "text")
    private String command;

    @Column(name = "exit_code", nullable = false)
    private Integer exitCode;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs;

    @Column(name = "log_excerpt", columnDefinition = "text")
    private String logExcerpt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TestRunStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TestRun() {
    }

    public TestRun(
            AgentRun agentRun,
            PatchRecord patch,
            String command,
            Integer exitCode,
            Integer durationMs,
            String logExcerpt,
            TestRunStatus status
    ) {
        this.agentRun = agentRun;
        this.patch = patch;
        this.command = command;
        this.exitCode = exitCode;
        this.durationMs = durationMs;
        this.logExcerpt = logExcerpt;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public AgentRun getAgentRun() {
        return agentRun;
    }

    public PatchRecord getPatch() {
        return patch;
    }

    public String getCommand() {
        return command;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getLogExcerpt() {
        return logExcerpt;
    }

    public TestRunStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
