package com.repopilot.pullrequest.domain;

import java.time.Instant;

import com.repopilot.agent.domain.AgentTask;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "pull_request_record")
public class PullRequestRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_task_id")
    private AgentTask agentTask;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patch_id")
    private PatchRecord patch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PullRequestProvider provider;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(columnDefinition = "text")
    private String url;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Column(name = "base_branch")
    private String baseBranch;

    @Column(name = "target_branch")
    private String targetBranch;

    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "commit_message", columnDefinition = "text")
    private String commitMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PullRequestStatus status;

    @Column(name = "remote_pushed_at")
    private Instant remotePushedAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PullRequestRecord() {
    }

    public PullRequestRecord(
            AgentTask agentTask,
            PatchRecord patch,
            PullRequestProvider provider,
            String title,
            String body,
            String baseBranch,
            String targetBranch,
            String commitSha,
            String commitMessage,
            PullRequestStatus status
    ) {
        this.agentTask = agentTask;
        this.patch = patch;
        this.provider = provider;
        this.title = title;
        this.body = body;
        this.baseBranch = baseBranch;
        this.targetBranch = targetBranch;
        this.commitSha = commitSha;
        this.commitMessage = commitMessage;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public AgentTask getAgentTask() {
        return agentTask;
    }

    public PatchRecord getPatch() {
        return patch;
    }

    public PullRequestProvider getProvider() {
        return provider;
    }

    public Integer getPrNumber() {
        return prNumber;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public PullRequestStatus getStatus() {
        return status;
    }

    public Instant getRemotePushedAt() {
        return remotePushedAt;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markRemotePushed() {
        this.remotePushedAt = Instant.now();
        this.errorMessage = null;
    }

    public void markOpen(Integer prNumber, String url) {
        this.prNumber = prNumber;
        this.url = url;
        this.status = PullRequestStatus.OPEN;
        this.openedAt = Instant.now();
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = PullRequestStatus.FAILED;
        this.errorMessage = errorMessage;
    }
}
