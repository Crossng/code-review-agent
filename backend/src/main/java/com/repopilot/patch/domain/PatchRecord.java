package com.repopilot.patch.domain;

import java.time.Instant;

import com.repopilot.agent.domain.AgentRun;
import com.repopilot.agent.domain.AgentTask;
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
@Table(name = "patch_record")
public class PatchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_task_id")
    private AgentTask agentTask;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id")
    private AgentRun agentRun;

    @Column(name = "base_branch", nullable = false)
    private String baseBranch;

    @Column(name = "target_branch", nullable = false)
    private String targetBranch;

    @Column(name = "diff_content", nullable = false, columnDefinition = "text")
    private String diffContent;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "generation_mode", nullable = false)
    private String generationMode;

    @Column(name = "generation_provider", nullable = false)
    private String generationProvider;

    @Column(name = "generation_model")
    private String generationModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PatchStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PatchRecord() {
    }

    public PatchRecord(
            AgentTask agentTask,
            AgentRun agentRun,
            String baseBranch,
            String targetBranch,
            String diffContent,
            String summary
    ) {
        this(agentTask, agentRun, baseBranch, targetBranch, diffContent, summary, "UNKNOWN");
    }

    public PatchRecord(
            AgentTask agentTask,
            AgentRun agentRun,
            String baseBranch,
            String targetBranch,
            String diffContent,
            String summary,
            String generationMode
    ) {
        this(agentTask, agentRun, baseBranch, targetBranch, diffContent, summary, generationMode, "UNKNOWN", null);
    }

    public PatchRecord(
            AgentTask agentTask,
            AgentRun agentRun,
            String baseBranch,
            String targetBranch,
            String diffContent,
            String summary,
            String generationMode,
            String generationProvider,
            String generationModel
    ) {
        this.agentTask = agentTask;
        this.agentRun = agentRun;
        this.baseBranch = baseBranch;
        this.targetBranch = targetBranch;
        this.diffContent = diffContent;
        this.summary = summary;
        this.generationMode = generationMode == null || generationMode.isBlank() ? "UNKNOWN" : generationMode;
        this.generationProvider = generationProvider == null || generationProvider.isBlank() ? "UNKNOWN" : generationProvider;
        this.generationModel = generationModel == null || generationModel.isBlank() ? null : generationModel;
        this.status = PatchStatus.GENERATED;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public AgentTask getAgentTask() {
        return agentTask;
    }

    public AgentRun getAgentRun() {
        return agentRun;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getDiffContent() {
        return diffContent;
    }

    public String getSummary() {
        return summary;
    }

    public String getGenerationMode() {
        return generationMode;
    }

    public String getGenerationProvider() {
        return generationProvider;
    }

    public String getGenerationModel() {
        return generationModel;
    }

    public PatchStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void approve() {
        this.status = PatchStatus.APPROVED;
    }

    public void markApplied() {
        this.status = PatchStatus.APPLIED;
    }

    public void reject() {
        this.status = PatchStatus.REJECTED;
    }
}
